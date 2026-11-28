package com.wanstu.cartograph.v4;

import static com.wanstu.cartograph.v4.RegionPageStore.Entry;

/** Capture-local immutable fact reuse. Hashes select slots; exact fields establish equality. */
public final class CaptureFactCache {
    private static final int SIZE=64;
    private record Fact(String material,String biome,FeatureDictionary.Terrain terrain,
                        int surface,int ground,int depth,int red,int green,int blue,Entry entry){
        boolean matches(String m,String b,FeatureDictionary.Terrain t,int s,int g,int d,int r,int gr,int bl){
            return surface==s&&ground==g&&depth==d&&red==r&&green==gr&&blue==bl&&terrain==t&&
                material.equals(m)&&biome.equals(b);
        }
    }
    private final Fact[] slots=new Fact[SIZE];
    private int hits,misses;
    public Entry entry(String material,String biome,FeatureDictionary.Terrain terrain,int surface,int ground,
                       int depth,int red,int green,int blue){
        int hash=material.hashCode();
        hash=31*hash+biome.hashCode();hash=31*hash+terrain.ordinal();
        hash=31*hash+surface;hash=31*hash+ground;hash=31*hash+depth;
        hash=31*hash+red;hash=31*hash+green;hash=31*hash+blue;
        int slot=(hash^(hash>>>16))&(SIZE-1);
        Fact f=slots[slot];
        if(f!=null&&f.matches(material,biome,terrain,surface,ground,depth,red,green,blue)){
            hits++;return f.entry;
        }
        Entry result=Entry.leaf(new FeatureDictionary.Feature(material,biome,terrain),surface,ground,depth,red,green,blue);
        slots[slot]=new Fact(material,biome,terrain,surface,ground,depth,red,green,blue,result);
        misses++;return result;
    }
    public int hits(){return hits;}
    public int misses(){return misses;}
}
