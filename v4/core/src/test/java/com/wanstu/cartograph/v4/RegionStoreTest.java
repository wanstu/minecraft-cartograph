package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.GeographicPyramid.*;

public final class RegionStoreTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    static void reject(IO test,String label)throws Exception{
        try{test.run();throw new AssertionError(label);}catch(IOException expected){}
    }
    interface IO{void run()throws Exception;}
    static void run(Path root)throws Exception{
        var grass=new FeatureDictionary.Feature("minecraft:grass_block[snowy=false]","minecraft:plains",FeatureDictionary.Terrain.GRASS);
        var water=new FeatureDictionary.Feature("minecraft:water[level=0]","minecraft:river",FeatureDictionary.Terrain.WATER);
        Entry a=Entry.leaf(grass,100,95,0,200,300,400);
        Entry b=Entry.leaf(water,62,50,12,100,200,300);
        Entry c=Entry.leaf(grass,UNKNOWN_HEIGHT,UNKNOWN_HEIGHT,0,300,400,500);
        RegionPageStore store=new RegionPageStore(root,3);
        DiskGeographicPyramid p=new DiskGeographicPyramid(store,10);
        check(p.update(-1,-1,a)==11,"local path");
        check(p.update(-2,-2,b)==11,"neighbor");
        check(p.update(-3,-3,c)==11,"partial");
        check(p.update(32,32,a)==11,"page boundary");
        check(p.update(-1,0,b)==11,"world axis boundary");
        check(p.update(-1,-1,a)==0,"no-op");
        check(store.diskWrites()==55,"one page per LOD");
        check(store.cachedPages()<=3,"bounded cache");
        var coarse=new Cell(10,-1,-1);
        store.clearCache();
        long reads=store.diskReads();
        var stats=p.get(coarse).stats();
        check(stats.known()==3&&stats.groundCount()==2&&stats.groundSum()==145&&stats.depthSum()==12,"weighted coarse");
        check(store.diskReads()==reads+1,"one page, no descendants");
        check(p.get(coarse).stats().equals(stats)&&store.diskReads()==reads+1,"cache hit");
        store=new RegionPageStore(root,2);p=new DiskGeographicPyramid(store,10);
        check(p.get(coarse).stats().equals(stats)&&store.diskReads()==1,"restart coarse only");
        check(a.equals(p.get(new Cell(0,-1,-1))),"stable palette after restart");
        check(p.get(new Cell(0,0,0))==null,"unknown");
        p.update(-2,-2,null);
        check(p.get(coarse).stats().known()==2&&p.get(coarse).stats().wetCount()==0,"incremental delete");
        check(p.get(new Cell(0,32,32)).equals(a),"unrelated page retained");
        check(p.get(new Cell(0,-1,0)).equals(b),"negative axis");
        // Coalesce an entire fresh chunk into one write per LOD, not 256 transactions.
        Entry[] chunk=new Entry[256];Arrays.fill(chunk,a);chunk[0]=b;chunk[1]=null;
        long before=store.diskWrites();
        check(p.updateChunk(-3,2,chunk)==11&&store.diskWrites()==before+11,"chunk write coalescing");
        var chunkStats=p.get(new Cell(4,-3,2)).stats();
        check(chunkStats.known()==255&&chunkStats.wetCount()==1&&chunkStats.groundSum()==254*95+50,"chunk oracle");
        check(p.updateChunk(-3,2,chunk)==0,"unchanged chunk");
        chunk[0]=a;chunk[1]=a;
        check(p.updateChunk(-3,2,chunk)==11,"chunk edit");
        check(p.get(new Cell(4,-3,2)).stats().known()==256&&p.get(new Cell(4,-3,2)).stats().wetCount()==0,"chunk edit oracle");
        Arrays.fill(chunk,null);
        check(p.updateChunk(-3,2,chunk)==11&&p.get(new Cell(4,-3,2))==null,"chunk clear");
        // Simulate a crash with an incomplete final append, after a durable batch.
        Key k0=new Key(0,10,10), k1=new Key(1,5,5);
        Page p0=new Page(k0,1,Map.of(0,a)),p1=new Page(k1,1,Map.of(0,new Entry(a.stats(),null)));
        byte[] d0=RegionPageStore.encode(p0);
        long flushes=store.durableFlushes();
        store.writeBatch(List.of(p0,p1));
        check(store.durableFlushes()==flushes+1,"one flush per batch");
        Path log=root.resolve("pages.cv4log");
        long durableLength=Files.size(log);
        try(var out=new DataOutputStream(Files.newOutputStream(log,StandardOpenOption.APPEND))){
            out.writeInt(0x4356344c);out.writeInt(4096);out.write(new byte[30]);
        }
        store=new RegionPageStore(root,2);
        check(store.read(k0).equals(p0)&&store.read(k1).equals(p1),"prior complete batch recovered");
        check(Files.size(log)==durableLength,"incomplete tail discarded");
        byte[] corrupt=d0.clone();corrupt[4]^=1;
        reject(()->RegionPageStore.decode(corrupt,k0),"checksum rejection");
        reject(()->RegionPageStore.decode(d0,k1),"wrong page rejection");
        RegionPageStore finalStore=store;
        try{finalStore.writeBatch(List.of(p0));throw new AssertionError("stale revision");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: V4 page palette, 55 incremental writes, 256-column chunk coalescing, direct coarse read, restart, bounded cache, deletion, incomplete append, corruption and revision checks");
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-v4-test-");
        try{run(root);}finally{
            try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
