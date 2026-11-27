package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.GeographicPyramid.*;

public final class FreshRegionBuilderTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    static void expectIO(Throwing action)throws Exception{
        try{action.run();throw new AssertionError("expected IOException");}catch(IOException expected){}
    }
    interface Throwing{void run()throws Exception;}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-fresh-oracle-");
        try{
            RegionShard.Id id=new RegionShard.Id(-1,1);
            FeatureDictionary dictionary=new FeatureDictionary();
            var grass=new FeatureDictionary.Feature("minecraft:grass_block","minecraft:plains",FeatureDictionary.Terrain.GRASS);
            var water=new FeatureDictionary.Feature("minecraft:water","minecraft:river",FeatureDictionary.Terrain.WATER);
            int g=dictionary.intern(grass),w=dictionary.intern(water);
            GeographicPyramid oracle=new GeographicPyramid(10,dictionary);
            Map<Long,Entry[]> chunks=new HashMap<>();
            Random random=new Random(20261127);
            Set<Cell> visited=new HashSet<>();
            int[][] coordinates={{-64,64},{-63,64},{-64,65},{-63,65},{-1,127},{-1,64},{-33,95},{-32,96}};
            for(var coord:coordinates){
                int cx=coord[0],cz=coord[1];
                Entry[] data=new Entry[256];chunks.put(key(cx,cz),data);
                for(int i=0;i<256;i++){
                    int x=cx*16+i%16,z=cz*16+i/16;
                    if((cx<=-63&&cz<=65)||random.nextInt(5)!=0){
                        boolean wet=random.nextBoolean();
                        int surface=random.nextInt(400)-64,ground=random.nextInt(8)==0?UNKNOWN_HEIGHT:surface-2;
                        if(random.nextInt(8)==0)surface=UNKNOWN_HEIGHT;
                        int r=random.nextInt(65536),green=random.nextInt(65536),b=random.nextInt(65536);
                        data[i]=Entry.leaf(wet?water:grass,surface,ground,wet?2:0,r,green,b);
                        oracle.update(x,z,new Sample(wet?w:g,surface,ground,wet?2:0,r,green,b));
                    }
                    Cell c=new Cell(0,x,z);
                    for(int lod=0;lod<=10;lod++){visited.add(c);c=c.parent();}
                }
            }
            var result=FreshRegionBuilder.build(root,id,(x,z)->chunks.get(key(x,z)));
            check(result.chunks()==coordinates.length,"observed chunks only");
            check(result.flushes()==1,"staged shard forced once before publication");
            var shard=new RegionShard(root,id,16,8*1024*1024);
            for(Cell cell:visited){
                Stats expected=oracle.at(cell);Entry actual=shard.get(cell);
                check(expected==null?actual==null:actual!=null&&Aggregate.from(expected).equals(actual.stats()),"aggregate "+cell);
                if(cell.level()==0&&actual!=null)
                    check(dictionary.get(oracle.sampleAt(cell.x(),cell.z()).featureId()).equals(actual.feature()),"feature "+cell);
            }
            check(shard.get(new Cell(0,-100,1100))==null,"unobserved region stays unknown");
            expectIO(()->FreshRegionBuilder.build(root,id,(x,z)->null));
            // A staged build failure never publishes a partially built shard.
            RegionShard.Id failed=new RegionShard.Id(2,-1);
            expectIO(()->FreshRegionBuilder.build(root,failed,(x,z)->{throw new IOException("source failed");}));
            check(!Files.exists(failed.directory(root)),"partial shard unpublished");
            // Fail after enough dense pages to have opened the staged log and written a frame.
            RegionShard.Id midFailed=new RegionShard.Id(3,-1);
            var counter=new java.util.concurrent.atomic.AtomicInteger();
            var repeated=FreshRegionBuilder.Columns.fromEntries(chunks.get(key(-64,64)));
            expectIO(()->FreshRegionBuilder.buildPacked(root,midFailed,(x,z)->{
                if(counter.incrementAndGet()>1000)throw new IOException("mid-build source failure");
                return repeated;
            }));
            check(counter.get()==1001&&!Files.exists(midFailed.directory(root)),"mid-build stage unpublished");
            try(var children=Files.list(root)){
                check(children.noneMatch(path->path.getFileName().toString().startsWith(".building-")),"staging cleaned");
            }
            // Incremental updates read and revise the fresh-built pages normally.
            Entry[] replacement=new Entry[256];
            Arrays.fill(replacement,Entry.leaf(grass,100,99,0,20,30,40));
            shard.updateChunks(List.of(new DiskGeographicPyramid.ChunkSnapshot(-64,64,replacement)));
            check(shard.get(new Cell(0,-1024,1024)).equals(replacement[0]),"incremental leaf after fresh build");
            shard=new RegionShard(root,id,16,8*1024*1024);
            check(shard.get(new Cell(0,-1024,1024)).equals(replacement[0]),"incremental reopen");
            check(RegionShard.Id.ofCell(new Cell(10,-1,1)).equals(id),"negative coordinate ownership");
            System.out.println("PASS: fresh builder vs independent oracle, "+visited.size()+" exact L0-L10 comparisons; dense/sparse coverage, negative coordinates, no overwrite, staging failure and incremental reopen");
        }finally{ScaleTest.delete(root);}
    }
    private static long key(int x,int z){return ((long)x<<32)|Integer.toUnsignedLong(z);}
}
