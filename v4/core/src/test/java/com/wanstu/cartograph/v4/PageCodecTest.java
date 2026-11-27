package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.util.Map;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class PageCodecTest {
    private static void check(boolean condition,String label){
        if(!condition)throw new AssertionError(label);
    }
    public static void main(String[] args)throws Exception{
        // Full-width unsigned counters/sums: exact at the width transitions and L10.
        for(int lod=1;lod<=10;lod++){
            long count=1L<<(2*lod);
            long[] hist=new long[FeatureDictionary.Terrain.values().length];
            hist[0]=count;
            var aggregate=new Aggregate(count,count,count,-2147483647L*count,
                2147483647L*count,65535L*count,65534L*count,32768L*count,
                count,65535L*count,Integer.MAX_VALUE,Integer.MAX_VALUE,hist);
            var key=new Key(lod,-3,5);
            var page=new Page(key,7,Map.of(1023,new Entry(aggregate,null)));
            byte[] encoded=RegionPageStore.encode(page);
            check(page.equals(RegionPageStore.decode(encoded,key)),"full LOD "+lod);
            check(java.util.Arrays.equals(encoded,RegionPageStore.encode(page)),"deterministic codec");
            byte[] truncated=java.util.Arrays.copyOf(encoded,encoded.length-1);
            try{RegionPageStore.decode(truncated,key);throw new AssertionError("accepted truncated payload");}
            catch(IOException expected){}
        }
        var feature=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        var key=new Key(0,0,0);
        var page=new Page(key,1,Map.of(0,Entry.leaf(feature,Integer.MAX_VALUE,Integer.MIN_VALUE,65535,65535,32768,0)));
        check(page.equals(RegionPageStore.decode(RegionPageStore.encode(page),key)),"leaf extremes");
        System.out.println("PASS: exact unsigned widths, all LODs, extreme leaf, deterministic codec and truncated payload rejection");
    }
}
