/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.RegionTransition;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.executor.EventType;
import org.apache.hadoop.hbase.regionserver.SplitTransaction.LoggingProgressable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.ZKAssign;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;

/**
 * Executes region merge as a "transaction". It is similar with
 * SplitTransaction. Call {@link #prepare(RegionServerServices)} to setup the
 * transaction, {@link #execute(Server, RegionServerServices)} to run the
 * transaction and {@link #rollback(Server, RegionServerServices)} to cleanup if
 * execute fails.
 * 
 * <p>
 * Here is an example of how you would use this class:
 * 
 * <pre>
 *  RegionMergeTransaction mt = new RegionMergeTransaction(this.conf, parent, midKey)
 *  if (!mt.prepare(services)) return;
 *  try {
 *    mt.execute(server, services);
 *  } catch (IOException ioe) {
 *    try {
 *      mt.rollback(server, services);
 *      return;
 *    } catch (RuntimeException e) {
 *      myAbortable.abort("Failed merge, abort");
 *    }
 *  }
 * </Pre>
 * <p>
 * This class is not thread safe. Caller needs ensure merge is run by one thread
 * only.
 */
@InterfaceAudience.Private
public class RegionMergeTransaction {
  private static final Log LOG = LogFactory.getLog(RegionMergeTransaction.class);

  // Merged region info
  private HRegionInfo mergedRegionInfo;
  // region_a sorts before region_b
  private final HRegion region_a;
  private final HRegion region_b;
  // merges dir is under region_a
  private final Path mergesdir;
  private int znodeVersion = -1;
  // We only merge adjacent regions if forcible is false
  private final boolean forcible;

  /**
   * Types to add to the transaction journal. Each enum is a step in the merge
   * transaction. Used to figure how much we need to rollback.
   */
  enum JournalEntry {
    /**
     * Set region as in transition, set it into MERGING state.
     */
    SET_MERGING_IN_ZK,
    /**
     * We created the temporary merge data directory.
     */
    CREATED_MERGE_DIR,
    /**
     * Closed the merging region A.
     */
    CLOSED_REGION_A,
    /**
     * The merging region A has been taken out of the server's online regions list.
     */
    OFFLINED_REGION_A,
    /**
     * Closed the merging region B.
     */
    CLOSED_REGION_B,
    /**
     * The merging region B has been taken out of the server's online regions list.
     */
    OFFLINED_REGION_B,
    /**
     * Started in on creation of the merged region.
     */
    STARTED_MERGED_REGION_CREATION,
    /**
     * Point of no return. If we got here, then transaction is not recoverable
     * other than by crashing out the regionserver.
     */
    PONR
  }

  /*
   * Journal of how far the merge transaction has progressed.
   */
  private final List<JournalEntry> journal = new ArrayList<JournalEntry>();

  private static IOException closedByOtherException = new IOException(
      "Failed to close region: already closed by another thread");

  /**
   * Constructor
   * @param a region a to merge
   * @param b region b to merge
   * @param forcible if false, we will only merge adjacent regions
   */
  public RegionMergeTransaction(final HRegion a, final HRegion b,
      final boolean forcible) {
    if (a.getRegionInfo().compareTo(b.getRegionInfo()) <= 0) {
      this.region_a = a;
      this.region_b = b;
    } else {
      this.region_a = b;
      this.region_b = a;
    }
    this.forcible = forcible;
    this.mergesdir = region_a.getRegionFileSystem().getMergesDir();
  }

  /**
   * Does checks on merge inputs.
   * @param services
   * @return <code>true</code> if the regions are mergeable else
   *         <code>false</code> if they are not (e.g. its already closed, etc.).
   */
  public boolean prepare(final RegionServerServices services) {
    if (!region_a.getTableDesc().getNameAsString()
        .equals(region_b.getTableDesc().getNameAsString())) {
      LOG.info("Can't merge regions " + region_a + "," + region_b
          + " because they do not belong to the same table");
      return false;
    }
    if (region_a.getRegionInfo().equals(region_b.getRegionInfo())) {
      LOG.info("Can't merge the same region " + region_a);
      return false;
    }
    if (!forcible && !HRegionInfo.areAdjacent(region_a.getRegionInfo(),
            region_b.getRegionInfo())) {
      String msg = "Skip merging " + this.region_a.getRegionNameAsString()
          + " and " + this.region_b.getRegionNameAsString()
          + ", because they are not adjacent.";
      LOG.info(msg);
      return false;
    }
    if (!this.region_a.isMergeable() || !this.region_b.isMergeable()) {
      return false;
    }
    try {
      boolean regionAHasMergeQualifier = hasMergeQualifierInMeta(services,
          region_a.getRegionName());
      if (regionAHasMergeQualifier ||
          hasMergeQualifierInMeta(services, region_b.getRegionName())) {
        LOG.debug("Region " + (regionAHasMergeQualifier ? region_a.getRegionNameAsString()
                : region_b.getRegionNameAsString())
            + " is not mergeable because it has merge qualifier in META");
        return false;
      }
    } catch (IOException e) {
      LOG.warn("Failed judging whether merge transaction is available for "
              + region_a.getRegionNameAsString() + " and "
              + region_b.getRegionNameAsString(), e);
      return false;
    }

    // WARN: make sure there is no parent region of the two merging regions in
    // .META. If exists, fixing up daughters would cause daughter regions(we
    // have merged one) online again when we restart master, so we should clear
    // the parent region to prevent the above case
    // Since HBASE-7721, we don't need fix up daughters any more. so here do
    // nothing

    this.mergedRegionInfo = getMergedRegionInfo(region_a.getRegionInfo(),
        region_b.getRegionInfo());
    return true;
  }

  /**
   * Run the transaction.
   * @param server Hosting server instance. Can be null when testing (won't try
   *          and update in zk if a null server)
   * @param services Used to online/offline regions.
   * @throws IOException If thrown, transaction failed. Call
   *           {@link #rollback(Server, RegionServerServices)}
   * @return merged region
   * @throws IOException
   * @see #rollback(Server, RegionServerServices)
   */
  public HRegion execute(final Server server,
      final RegionServerServices services) throws IOException {
    HRegion mergedRegion = createMergedRegion(server, services);
    openMergedRegion(server, services, mergedRegion);
    transitionZKNode(server, services);
    return mergedRegion;
  }

  /**
   * Prepare the merged region and region files.
   * @param server Hosting server instance. Can be null when testing (won't try
   *          and update in zk if a null server)
   * @param services Used to online/offline regions.
   * @return merged region
   * @throws IOException If thrown, transaction failed. Call
   *           {@link #rollback(Server, RegionServerServices)}
   */
  HRegion createMergedRegion(final Server server,
      final RegionServerServices services) throws IOException {
    LOG.info("Starting merge of " + region_a + " and "
        + region_b.getRegionNameAsString() + ", forcible=" + forcible);
    if ((server != null && server.isStopped())
        || (services != null && services.isStopping())) {
      throw new IOException("Server is stopped or stopping");
    }

    // If true, no cluster to write meta edits to or to update znodes in.
    boolean testing = server == null ? true : server.getConfiguration()
        .getBoolean("hbase.testing.nocluster", false);

    // Set ephemeral MERGING znode up in zk. Mocked servers sometimes don't
    // have zookeeper so don't do zk stuff if server or zookeeper is null
    if (server != null && server.getZooKeeper() != null) {
      try {
        createNodeMerging(server.getZooKeeper(), this.mergedRegionInfo,
            server.getServerName());
      } catch (KeeperException e) {
        throw new IOException("Failed creating MERGING znode on "
            + this.mergedRegionInfo.getRegionNameAsString(), e);
      }
    }
    this.journal.add(JournalEntry.SET_MERGING_IN_ZK);
    if (server != null && server.getZooKeeper() != null) {
      try {
        // Transition node from MERGING to MERGING after creating the merge
        // node. Master will get the callback for node change only if the
        // transition is successful.
        // Note that if the transition fails then the rollback will delete the
        // created znode as the journal entry SET_MERGING_IN_ZK is added.
        this.znodeVersion = transitionNodeMerging(server.getZooKeeper(),
            this.mergedRegionInfo, server.getServerName(), -1);
      } catch (KeeperException e) {
        throw new IOException("Failed setting MERGING znode on "
            + this.mergedRegionInfo.getRegionNameAsString(), e);
      }
    }

    this.region_a.getRegionFileSystem().createMergesDir();
    this.journal.add(JournalEntry.CREATED_MERGE_DIR);

    Map<byte[], List<StoreFile>> hstoreFilesOfRegionA = closeAndOfflineRegion(
        services, this.region_a, true, testing);
    Map<byte[], List<StoreFile>> hstoreFilesOfRegionB = closeAndOfflineRegion(
        services, this.region_b, false, testing);

    assert hstoreFilesOfRegionA != null && hstoreFilesOfRegionB != null;


    //
    // mergeStoreFiles creates merged region dirs under the region_a merges dir
    // Nothing to unroll here if failure -- clean up of CREATE_MERGE_DIR will
    // clean this up.
    mergeStoreFiles(hstoreFilesOfRegionA, hstoreFilesOfRegionB);

    // Log to the journal that we are creating merged region. We could fail
    // halfway through. If we do, we could have left
    // stuff in fs that needs cleanup -- a storefile or two. Thats why we
    // add entry to journal BEFORE rather than AFTER the change.
    this.journal.add(JournalEntry.STARTED_MERGED_REGION_CREATION);
    HRegion mergedRegion = createMergedRegionFromMerges(this.region_a,
        this.region_b, this.mergedRegionInfo);


    // This is the point of no return. Similar with SplitTransaction.
    // IF we reach the PONR then subsequent failures need to crash out this
    // regionserver
    this.journal.add(JournalEntry.PONR);

    // Add merged region and delete region_a and region_b
    // as an atomic update. See HBASE-7721. This update to META makes the region
    // will determine whether the region is merged or not in case of failures.
    // If it is successful, master will roll-forward, if not, master will
    // rollback
    if (!testing) {
      MetaEditor.mergeRegions(server.getCatalogTracker(),
          mergedRegion.getRegionInfo(), region_a.getRegionInfo(),
          region_b.getRegionInfo(), server.getServerName());
    }
    return mergedRegion;
  }

  /**
   * Create a merged region from the merges directory under region a. In order
   * to mock it for tests, place it with a new method.
   * @param a hri of region a
   * @param b hri of region b
   * @param mergedRegion hri of merged region
   * @return merged HRegion.
   * @throws IOException
   */
  HRegion createMergedRegionFromMerges(final HRegion a, final HRegion b,
      final HRegionInfo mergedRegion) throws IOException {
    return a.createMergedRegionFromMerges(mergedRegion, b);
  }

  /**
   * Close the merging region and offline it in regionserver
   * @param services
   * @param region
   * @param isRegionA true if it is merging region a, false if it is region b
   * @param testing true if it is testing
   * @return a map of family name to list of store files
   * @throws IOException
   */
  private Map<byte[], List<StoreFile>> closeAndOfflineRegion(
      final RegionServerServices services, final HRegion region,
      final boolean isRegionA, final boolean testing) throws IOException {
    Map<byte[], List<StoreFile>> hstoreFilesToMerge = null;
    Exception exceptionToThrow = null;
    try {
      hstoreFilesToMerge = region.close(false);
    } catch (Exception e) {
      exceptionToThrow = e;
    }
    if (exceptionToThrow == null && hstoreFilesToMerge == null) {
      // The region was closed by a concurrent thread. We can't continue
      // with the merge, instead we must just abandon the merge. If we
      // reopen or merge this could cause problems because the region has
      // probably already been moved to a different server, or is in the
      // process of moving to a different server.
      exceptionToThrow = closedByOtherException;
    }
    if (exceptionToThrow != closedByOtherException) {
      this.journal.add(isRegionA ? JournalEntry.CLOSED_REGION_A
          : JournalEntry.CLOSED_REGION_B);
    }
    if (exceptionToThrow != null) {
      if (exceptionToThrow instanceof IOException)
        throw (IOException) exceptionToThrow;
      throw new IOException(exceptionToThrow);
    }

    if (!testing) {
      services.removeFromOnlineRegions(region, null);
    }
    this.journal.add(isRegionA ? JournalEntry.OFFLINED_REGION_A
        : JournalEntry.OFFLINED_REGION_B);
    return hstoreFilesToMerge;
  }

  /**
   * Get merged region info through the specified two regions
   * @param a merging region A
   * @param b merging region B
   * @return the merged region info
   */
  public static HRegionInfo getMergedRegionInfo(final HRegionInfo a,
      final HRegionInfo b) {
    long rid = EnvironmentEdgeManager.currentTimeMillis();
    // Regionid is timestamp. Merged region's id can't be less than that of
    // merging regions else will insert at wrong location in .META.
    if (rid < a.getRegionId() || rid < b.getRegionId()) {
      LOG.warn("Clock skew; merging regions id are " + a.getRegionId()
          + " and " + b.getRegionId() + ", but current time here is " + rid);
      rid = Math.max(a.getRegionId(), b.getRegionId()) + 1;
    }

    byte[] startKey = null;
    byte[] endKey = null;
    // Choose the smaller as start key
    if (a.compareTo(b) <= 0) {
      startKey = a.getStartKey();
    } else {
      startKey = b.getStartKey();
    }
    // Choose the bigger as end key
    if (a.getComparator().matchingRows(a.getEndKey(), 0, a.getEndKey().length,
        HConstants.EMPTY_BYTE_ARRAY, 0, HConstants.EMPTY_BYTE_ARRAY.length)
        || a.getComparator().compareRows(a.getEndKey(), 0,
            a.getEndKey().length, b.getEndKey(), 0, b.getEndKey().length) > 0) {
      endKey = a.getEndKey();
    } else {
      endKey = b.getEndKey();
    }

    // Merged region is sorted between two merging regions in META
    HRegionInfo mergedRegionInfo = new HRegionInfo(a.getTableName(), startKey,
        endKey, false, rid);
    return mergedRegionInfo;
  }

  /**
   * Perform time consuming opening of the merged region.
   * @param server Hosting server instance. Can be null when testing (won't try
   *          and update in zk if a null server)
   * @param services Used to online/offline regions.
   * @param merged the merged region
   * @throws IOException If thrown, transaction failed. Call
   *           {@link #rollback(Server, RegionServerServices)}
   */
  void openMergedRegion(final Server server,
      final RegionServerServices services, HRegion merged) throws IOException {
    boolean stopped = server != null && server.isStopped();
    boolean stopping = services != null && services.isStopping();
    if (stopped || stopping) {
      LOG.info("Not opening merged region  " + merged.getRegionNameAsString()
          + " because stopping=" + stopping + ", stopped=" + stopped);
      return;
    }
    HRegionInfo hri = merged.getRegionInfo();
    LoggingProgressable reporter = server == null ? null
        : new LoggingProgressable(hri, server.getConfiguration().getLong(
            "hbase.regionserver.regionmerge.open.log.interval", 10000));
    merged.openHRegion(reporter);

    if (services != null) {
      try {
        services.postOpenDeployTasks(merged, server.getCatalogTracker());
        services.addToOnlineRegions(merged);
      } catch (KeeperException ke) {
        throw new IOException(ke);
      }
    }

  }

  /**
   * Finish off merge transaction, transition the zknode
   * @param server Hosting server instance. Can be null when testing (won't try
   *          and update in zk if a null server)
   * @param services Used to online/offline regions.
   * @throws IOException If thrown, transaction failed. Call
   *           {@link #rollback(Server, RegionServerServices)}
   */
  void transitionZKNode(final Server server, final RegionServerServices services)
      throws IOException {
    if (server == null || server.getZooKeeper() == null) {
      return;
    }

    // Tell master about merge by updating zk. If we fail, abort.
    try {
      this.znodeVersion = transitionNodeMerge(server.getZooKeeper(),
          this.mergedRegionInfo, region_a.getRegionInfo(),
          region_b.getRegionInfo(), server.getServerName(), this.znodeVersion);

      long startTime = EnvironmentEdgeManager.currentTimeMillis();
      int spins = 0;
      // Now wait for the master to process the merge. We know it's done
      // when the znode is deleted. The reason we keep tickling the znode is
      // that it's possible for the master to miss an event.
      do {
        if (spins % 10 == 0) {
          LOG.debug("Still waiting on the master to process the merge for "
              + this.mergedRegionInfo.getEncodedName() + ", waited "
              + (EnvironmentEdgeManager.currentTimeMillis() - startTime) + "ms");
        }
        Thread.sleep(100);
        // When this returns -1 it means the znode doesn't exist
        this.znodeVersion = tickleNodeMerge(server.getZooKeeper(),
            this.mergedRegionInfo, region_a.getRegionInfo(),
            region_b.getRegionInfo(), server.getServerName(), this.znodeVersion);
        spins++;
      } while (this.znodeVersion != -1 && !server.isStopped()
          && !services.isStopping());
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IOException("Failed telling master about merge "
          + mergedRegionInfo.getEncodedName(), e);
    }


    // Leaving here, the mergedir with its dross will be in place but since the
    // merge was successful, just leave it; it'll be cleaned when region_a is
    // cleaned up by CatalogJanitor on master
  }

  /**
   * Create reference file(s) of merging regions under the region_a merges dir
   * @param hstoreFilesOfRegionA
   * @param hstoreFilesOfRegionB
   * @throws IOException
   */
  private void mergeStoreFiles(
      Map<byte[], List<StoreFile>> hstoreFilesOfRegionA,
      Map<byte[], List<StoreFile>> hstoreFilesOfRegionB)
      throws IOException {
    // Create reference file(s) of region A in mergdir
    HRegionFileSystem fs_a = this.region_a.getRegionFileSystem();
    for (Map.Entry<byte[], List<StoreFile>> entry : hstoreFilesOfRegionA
        .entrySet()) {
      String familyName = Bytes.toString(entry.getKey());
      for (StoreFile storeFile : entry.getValue()) {
        fs_a.mergeStoreFile(this.mergedRegionInfo, familyName, storeFile,
            this.mergesdir);
      }
    }
    // Create reference file(s) of region B in mergedir
    HRegionFileSystem fs_b = this.region_b.getRegionFileSystem();
    for (Map.Entry<byte[], List<StoreFile>> entry : hstoreFilesOfRegionB
        .entrySet()) {
      String familyName = Bytes.toString(entry.getKey());
      for (StoreFile storeFile : entry.getValue()) {
        fs_b.mergeStoreFile(this.mergedRegionInfo, familyName, storeFile,
            this.mergesdir);
      }
    }
  }

  /**
   * @param server Hosting server instance (May be null when testing).
   * @param services Services of regionserver, used to online regions.
   * @throws IOException If thrown, rollback failed. Take drastic action.
   * @return True if we successfully rolled back, false if we got to the point
   *         of no return and so now need to abort the server to minimize
   *         damage.
   */
  public boolean rollback(final Server server,
      final RegionServerServices services) throws IOException {
    assert this.mergedRegionInfo != null;
    boolean result = true;
    ListIterator<JournalEntry> iterator = this.journal
        .listIterator(this.journal.size());
    // Iterate in reverse.
    while (iterator.hasPrevious()) {
      JournalEntry je = iterator.previous();
      switch (je) {

        case SET_MERGING_IN_ZK:
          if (server != null && server.getZooKeeper() != null) {
            cleanZK(server, this.mergedRegionInfo);
          }
          break;

        case CREATED_MERGE_DIR:
          this.region_a.writestate.writesEnabled = true;
          this.region_b.writestate.writesEnabled = true;
          this.region_a.getRegionFileSystem().cleanupMergesDir();
          break;

        case CLOSED_REGION_A:
          try {
            // So, this returns a seqid but if we just closed and then reopened,
            // we should be ok. On close, we flushed using sequenceid obtained
            // from hosting regionserver so no need to propagate the sequenceid
            // returned out of initialize below up into regionserver as we
            // normally do.
            this.region_a.initialize();
          } catch (IOException e) {
            LOG.error("Failed rollbacking CLOSED_REGION_A of region "
                + this.region_a.getRegionNameAsString(), e);
            throw new RuntimeException(e);
          }
          break;

        case OFFLINED_REGION_A:
          if (services != null)
            services.addToOnlineRegions(this.region_a);
          break;

        case CLOSED_REGION_B:
          try {
            this.region_b.initialize();
          } catch (IOException e) {
            LOG.error("Failed rollbacking CLOSED_REGION_A of region "
                + this.region_b.getRegionNameAsString(), e);
            throw new RuntimeException(e);
          }
          break;

        case OFFLINED_REGION_B:
          if (services != null)
            services.addToOnlineRegions(this.region_b);
          break;

        case STARTED_MERGED_REGION_CREATION:
          this.region_a.getRegionFileSystem().cleanupMergedRegion(
              this.mergedRegionInfo);
          break;

        case PONR:
          // We got to the point-of-no-return so we need to just abort. Return
          // immediately. Do not clean up created merged regions.
          return false;

        default:
          throw new RuntimeException("Unhandled journal entry: " + je);
      }
    }
    return result;
  }

  HRegionInfo getMergedRegionInfo() {
    return this.mergedRegionInfo;
  }

  // For unit testing.
  Path getMergesDir() {
    return this.mergesdir;
  }

  private static void cleanZK(final Server server, final HRegionInfo hri) {
    try {
      // Only delete if its in expected state; could have been hijacked.
      ZKAssign.deleteNode(server.getZooKeeper(), hri.getEncodedName(),
          EventType.RS_ZK_REGION_MERGING);
    } catch (KeeperException.NoNodeException e) {
      LOG.warn("Failed cleanup zk node of " + hri.getRegionNameAsString(), e);
    } catch (KeeperException e) {
      server.abort("Failed cleanup zk node of " + hri.getRegionNameAsString(),e);
    }

  }

  /**
   * Creates a new ephemeral node in the MERGING state for the merged region.
   * Create it ephemeral in case regionserver dies mid-merge.
   * 
   * <p>
   * Does not transition nodes from other states. If a node already exists for
   * this region, a {@link NodeExistsException} will be thrown.
   *
   * @param zkw zk reference
   * @param region region to be created as offline
   * @param serverName server event originates from
   * @return Version of znode created.
   * @throws KeeperException
   * @throws IOException
   */
  int createNodeMerging(final ZooKeeperWatcher zkw, final HRegionInfo region,
      final ServerName serverName) throws KeeperException, IOException {
    LOG.debug(zkw.prefix("Creating ephemeral node for "
        + region.getEncodedName() + " in MERGING state"));
    RegionTransition rt = RegionTransition.createRegionTransition(
        EventType.RS_ZK_REGION_MERGING, region.getRegionName(), serverName);
    String node = ZKAssign.getNodeName(zkw, region.getEncodedName());
    if (!ZKUtil.createEphemeralNodeAndWatch(zkw, node, rt.toByteArray())) {
      throw new IOException("Failed create of ephemeral " + node);
    }
    // Transition node from MERGING to MERGING and pick up version so we
    // can be sure this znode is ours; version is needed deleting.
    return transitionNodeMerging(zkw, region, serverName, -1);
  }

  /**
   * Transitions an existing node for the specified region which is currently in
   * the MERGING state to be in the MERGE state. Converts the ephemeral MERGING
   * znode to an ephemeral MERGE node. Master cleans up MERGE znode when it
   * reads it (or if we crash, zk will clean it up).
   *
   * <p>
   * Does not transition nodes from other states. If for some reason the node
   * could not be transitioned, the method returns -1. If the transition is
   * successful, the version of the node after transition is returned.
   *
   * <p>
   * This method can fail and return false for three different reasons:
   * <ul>
   * <li>Node for this region does not exist</li>
   * <li>Node for this region is not in MERGING state</li>
   * <li>After verifying MERGING state, update fails because of wrong version
   * (this should never actually happen since an RS only does this transition
   * following a transition to MERGING. if two RS are conflicting, one would
   * fail the original transition to MERGING and not this transition)</li>
   * </ul>
   *
   * <p>
   * Does not set any watches.
   *
   * <p>
   * This method should only be used by a RegionServer when completing the open
   * of merged region.
   *
   * @param zkw zk reference
   * @param merged region to be transitioned to opened
   * @param a merging region A
   * @param b merging region B
   * @param serverName server event originates from
   * @param znodeVersion expected version of data before modification
   * @return version of node after transition, -1 if unsuccessful transition
   * @throws KeeperException if unexpected zookeeper exception
   * @throws IOException
   */
  private static int transitionNodeMerge(ZooKeeperWatcher zkw,
      HRegionInfo merged, HRegionInfo a, HRegionInfo b, ServerName serverName,
      final int znodeVersion) throws KeeperException, IOException {
    byte[] payload = HRegionInfo.toDelimitedByteArray(merged, a, b);
    return ZKAssign.transitionNode(zkw, merged, serverName,
        EventType.RS_ZK_REGION_MERGING, EventType.RS_ZK_REGION_MERGE,
        znodeVersion, payload);
  }

  /**
   *
   * @param zkw zk reference
   * @param parent region to be transitioned to merging
   * @param serverName server event originates from
   * @param version znode version
   * @return version of node after transition, -1 if unsuccessful transition
   * @throws KeeperException
   * @throws IOException
   */
  int transitionNodeMerging(final ZooKeeperWatcher zkw,
      final HRegionInfo parent, final ServerName serverName, final int version)
      throws KeeperException, IOException {
    return ZKAssign.transitionNode(zkw, parent, serverName,
            EventType.RS_ZK_REGION_MERGING, EventType.RS_ZK_REGION_MERGING,
        version);
  }

  private static int tickleNodeMerge(ZooKeeperWatcher zkw, HRegionInfo merged,
      HRegionInfo a, HRegionInfo b, ServerName serverName,
      final int znodeVersion) throws KeeperException, IOException {
    byte[] payload = HRegionInfo.toDelimitedByteArray(a, b);
    return ZKAssign.transitionNode(zkw, merged, serverName,
        EventType.RS_ZK_REGION_MERGE, EventType.RS_ZK_REGION_MERGE,
        znodeVersion, payload);
  }

  /**
   * Checks if the given region has merge qualifier in .META.
   * @param services
   * @param regionName name of specified region
   * @return true if the given region has merge qualifier in META.(It will be
   *         cleaned by CatalogJanitor)
   * @throws IOException
   */
  boolean hasMergeQualifierInMeta(final RegionServerServices services,
      final byte[] regionName) throws IOException {
    if (services == null) return false;
    // Get merge regions if it is a merged region and already has merge
    // qualifier
    Pair<HRegionInfo, HRegionInfo> mergeRegions = MetaReader
        .getRegionsFromMergeQualifier(services.getCatalogTracker(), regionName);
    if (mergeRegions != null &&
        (mergeRegions.getFirst() != null || mergeRegions.getSecond() != null)) {
      // It has merge qualifier
      return true;
    }
    return false;
  }
}

