package com.wanstu.cartograph.v4;

import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.GeographicPyramid.*;

public final class DensePyramidTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-dense-");
        try{
            FeatureDictionary dict=new FeatureDictionary();
            var grass=new FeatureDictionary.Feature("minecraft:grass_block","minecraft:plains",FeatureDictionary.Terrain.GRASS);
            var water=new FeatureDictionary.Feature("minecraft:water","minecraft:river",FeatureDictionary.Terrain.WATER);
            int g=dict.intern(grass),w=dict.intern(water);
            var oracle=new GeographicPyramid(10,dict);
            var store=new RegionPageStore(root,32,8*1024*1024);
            var disk=new DiskGeographicPyramid(store,10);
            Random random=new Random(441L);
            Set<Cell> visited=new HashSet<>();
            int comparisons=0;
            for(int round=0;round<32;round++){
                List<DiskGeographicPyramid.ChunkSnapshot> batch=new ArrayList<>();
                for(int n=0;n<6;n++){
                    int cx=random.nextInt(8)-4,cz=random.nextInt(8)-4;
                    Entry[] data=new Entry[256];
                    for(int i=0;i<256;i++){
                        int x=cx*16+i%16,z=cz*16+i/16;
                        boolean known=random.nextInt(4)!=0;
                        boolean wet=random.nextBoolean();
                        int surface=64+random.nextInt(150),ground=random.nextInt(5)==0?UNKNOWN_HEIGHT:surface-2;
                        int depth=wet?2:0,r=random.nextInt(65536),green=random.nextInt(65536),b=random.nextInt(65536);
                        data[i]=known?Entry.leaf(wet?water:grass,surface,ground,depth,r,green,b):null;
                        oracle.update(x,z,known?new Sample(wet?w:g,surface,ground,depth,r,green,b):null);
                        Cell c=new Cell(0,x,z);
                        for(int lod=0;lod<=10;lod++){visited.add(c);c=c.parent();}
                    }
                    batch.add(new DiskGeographicPyramid.ChunkSnapshot(cx,cz,data));
                }
                disk.updateChunks(batch);
                if(round%8==7){
                    store=new RegionPageStore(root,32,8*1024*1024);
                    disk=new DiskGeographicPyramid(store,10);
                    for(Cell c:visited){
                        Stats expected=oracle.at(c);Entry actual=disk.get(c);
                        check(expected==null?actual==null:actual!=null&&Aggregate.from(expected).equals(actual.stats()),"aggregate "+c+" round="+round);
                        if(c.level()==0&&actual!=null)
                            check(dict.get(oracle.sampleAt(c.x(),c.z()).featureId()).equals(actual.feature()),"feature "+c);
                        comparisons++;
                    }
                }
            }
            System.out.println("PASS: dense batch oracle, "+comparisons+" comparisons across negative/positive page boundaries, repeated edits/deletes and reopen");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
