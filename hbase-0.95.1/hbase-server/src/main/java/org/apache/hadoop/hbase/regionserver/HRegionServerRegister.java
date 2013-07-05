package org.apache.hadoop.hbase.regionserver;

/**
 * Created with IntelliJ IDEA.
 * User: Wang Yufei
 * Date: 13-7-5
 * Time: 上午11:48
 * To change this template use File | Settings | File Templates.
 */
public class HRegionServerRegister {

    private static HRegionServer last;

    public static void registerInstance(HRegionServer instance) {
        last = instance;
    }

    public static HRegionServer getLast() {
        return last;
    }

}
