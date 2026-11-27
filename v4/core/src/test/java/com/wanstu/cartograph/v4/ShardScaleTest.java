package com.wanstu.cartograph.v4;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.GeographicPyramid.*;

public final class ShardScaleTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    record Result(long writes,long flushes,long cacheBytes){}
    static Result build(Path root,RegionShard.Id id,Entry[][] samples,int batch,int cacheLimit,boolean fresh,boolean unique,FreshRegionBuilder.Columns[] packed)throws Exception{
        if(fresh){
            var result=FreshRegionBuilder.buildPacked(root,id,(x,z)->unique?uniqueColumns(x,z):packed[Math.floorMod(z,4)*4+Math.floorMod(x,4)]);
            check(result.chunks()==4096,"fresh chunks");return new Result(result.pages(),result.flushes(),0);
        }
        RegionShard shard=new RegionShard(root,id,128,cacheLimit);
        var pending=new ArrayList<DiskGeographicPyramid.ChunkSnapshot>(batch);
        int cx=id.x()*RegionShard.CHUNKS,cz=id.z()*RegionShard.CHUNKS;
        int tile=batch>=256?16:8;
        for(int tz=0;tz<RegionShard.CHUNKS;tz+=tile)for(int tx=0;tx<RegionShard.CHUNKS;tx+=tile)
          for(int gz=tz;gz<tz+tile;gz+=4)for(int gx=tx;gx<tx+tile;gx+=4)
            for(int dz=0;dz<4;dz++)for(int dx=0;dx<4;dx++){
                pending.add(new DiskGeographicPyramid.ChunkSnapshot(cx+gx+dx,cz+gz+dz,samples[(dz*4+dx)%samples.length]));
                if(pending.size()==batch){shard.updateChunks(pending);pending.clear();}
            }
        if(!pending.isEmpty())shard.updateChunks(pending);
        check(shard.cacheBytes()<=cacheLimit,"cache budget");
        return new Result(shard.diskWrites(),shard.flushes(),shard.cacheBytes());
    }
    private static final FeatureDictionary.Feature GRASS=new FeatureDictionary.Feature("minecraft:grass_block","minecraft:plains",FeatureDictionary.Terrain.GRASS);
    private static final FeatureDictionary.Feature WATER=new FeatureDictionary.Feature("minecraft:water","minecraft:river",FeatureDictionary.Terrain.WATER);
    private static SplittableRandom random(int x,int z){
        return new SplittableRandom(((long)x<<32)^Integer.toUnsignedLong(z)^0x77aa9977L);
    }
    private static FreshRegionBuilder.Columns uniqueColumns(int x,int z){
        var rand=random(x,z);var f=new FeatureDictionary.Feature[256];
        int[] s=new int[256],g=new int[256];char[] d=new char[256],r=new char[256],gr=new char[256],b=new char[256];
        for(int i=0;i<256;i++){
            boolean wet=rand.nextInt(13)==0;int h=wet?62:64+rand.nextInt(40);
            f[i]=wet?WATER:GRASS;s[i]=h;g[i]=wet?h-6:h;d[i]=(char)(wet?6:0);
            r[i]=(char)(10000+rand.nextInt(1024));gr[i]=(char)(20000+rand.nextInt(1024));b[i]=(char)(10000+rand.nextInt(1024));
        }
        return new FreshRegionBuilder.Columns(f,s,g,d,r,gr,b);
    }
    private static Entry uniqueFirst(int x,int z){
        var rand=random(x,z);boolean wet=rand.nextInt(13)==0;int h=wet?62:64+rand.nextInt(40);
        return Entry.leaf(wet?WATER:GRASS,h,wet?h-6:h,wet?6:0,
            10000+rand.nextInt(1024),20000+rand.nextInt(1024),10000+rand.nextInt(1024));
    }
    public static void main(String[] args)throws Exception{
        int edge=Integer.parseInt(args[0]),workers=Integer.parseInt(args[1]),batch=Integer.parseInt(args[2]);
        if(edge<1||edge>16||workers<1||workers>4||batch!=64&&batch!=256)throw new IllegalArgumentException("benchmark bounds");
        boolean unique=args.length>3&&args[3].equals("unique");
        boolean fresh=unique||args.length>3&&args[3].equals("fresh");
        var root=Files.createTempDirectory("cartograph-v4-shard-bench-");
        ExecutorService pool=Executors.newFixedThreadPool(workers);
        try{
            Entry[][] samples=ScaleTest.samples();
            var packed=new FreshRegionBuilder.Columns[samples.length];
            for(int i=0;i<samples.length;i++)packed[i]=FreshRegionBuilder.Columns.fromEntries(samples[i]);
            List<RegionShard.Id> ids=new ArrayList<>();
            for(int z=-edge/2;z<edge-edge/2;z++)for(int x=-edge/2;x<edge-edge/2;x++)ids.add(new RegionShard.Id(x,z));
            int cacheLimit=16*1024*1024/workers;
            var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long cpuStart=os.getProcessCpuTime(),start=System.nanoTime();
            List<Future<Result>> jobs=new ArrayList<>();
            // Bounded benchmark workload; at most workers tasks execute concurrently.
            for(var id:ids)jobs.add(pool.submit(()->build(root,id,samples,batch,cacheLimit,fresh,unique,packed)));
            long writes=0,flushes=0;
            for(var future:jobs){var r=future.get();writes+=r.writes;flushes+=r.flushes;}
            double seconds=(System.nanoTime()-start)/1e9,cpu=(os.getProcessCpuTime()-cpuStart)/1e9;
            int chunks=ids.size()*4096;
            long known=0,reads=0;
            for(var id:ids){
                var shard=new RegionShard(root,id,8,cacheLimit);
                var summary=shard.get(new Cell(10,id.x(),id.z()));
                check(summary!=null&&summary.stats().known()==1048576,"exact shard coverage");
                known+=summary.stats().known();reads+=shard.diskReads();
                Entry expected=unique?uniqueFirst(id.x()*64,id.z()*64):samples[0][0];
                check(shard.get(new Cell(0,id.x()*1024,id.z()*1024)).equals(expected),"leaf reopen");
                check(RegionShard.Id.ofCell(new Cell(10,id.x(),id.z())).equals(id),"root routing");
                check(RegionShard.Id.ofChunk(id.x()*64+63,id.z()*64+63).equals(id),"negative edge routing");
            }
            check(known==chunks*256L,"world coverage");
            long bytes;
            try(var files=Files.walk(root)){bytes=files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum();}
            System.out.printf(Locale.ROOT,"fresh=%s unique=%s shards=%d workers=%d chunks=%d batch=%d seconds=%.3f chunks/s=%.1f cpuSeconds=%.3f avgCpuCores=%.2f cpuMicrosPerChunk=%.2f pageWrites=%d flushes=%d diskBytes=%d overviewReads=%d coverage=%d%n",
                fresh,unique,ids.size(),workers,chunks,batch,seconds,chunks/seconds,cpu,cpu/seconds,cpu*1e6/chunks,writes,flushes,bytes,reads,known);
        }finally{
            pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("workers still running");
            ScaleTest.delete(root);
        }
    }
}
