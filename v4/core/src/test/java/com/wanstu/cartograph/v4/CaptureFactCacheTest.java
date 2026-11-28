package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Entry;
import static com.wanstu.cartograph.v4.FeatureDictionary.Terrain;

public final class CaptureFactCacheTest {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        var flat=new CaptureFactCache();
        var first=flat.entry("minecraft:stone","minecraft:plains",Terrain.ROCK,80,80,0,100,200,300);
        for(int i=1;i<256;i++)check(flat.entry("minecraft:stone","minecraft:plains",Terrain.ROCK,80,80,0,100,200,300)==first,"same immutable fact");
        check(flat.hits()==255&&flat.misses()==1,"one leaf allocation for flat chunk");
        var cache=new CaptureFactCache();
        // Equal Java string hashes must never imply equal material identity.
        var a=cache.entry("test:Aa","test:biome",Terrain.OTHER,80,80,0,100,200,300);
        var b=cache.entry("test:BB","test:biome",Terrain.OTHER,80,80,0,100,200,300);
        check("test:Aa".hashCode()=="test:BB".hashCode()&&!a.equals(b),"collision exactness");
        var random=new Random(1667);
        for(int i=0;i<10000;i++){
            String m="test:m"+random.nextInt(8),biome="test:b"+random.nextInt(3);
            Terrain t=Terrain.values()[random.nextInt(8)];
            int surface=random.nextInt(8)+60,ground=random.nextBoolean()?surface-1:GeographicPyramid.UNKNOWN_HEIGHT;
            int depth=random.nextInt(5),r=random.nextInt(7),g=random.nextInt(7),bl=random.nextInt(7);
            var observed=cache.entry(m,biome,t,surface,ground,depth,r,g,bl);
            check(observed.equals(Entry.leaf(new FeatureDictionary.Feature(m,biome,t),surface,ground,depth,r,g,bl)),"exact direct oracle");
            check(cache.entry(m,biome,t,surface,ground,depth,r,g,bl)==observed,"immediate reuse");
        }
        System.out.println("PASS: capture fact cache 255/256 flat reuse, exact hash collision, 10000 direct fact comparisons");
    }
}
