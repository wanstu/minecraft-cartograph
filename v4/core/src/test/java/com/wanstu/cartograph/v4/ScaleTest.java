package com.wanstu.cartograph.v4;

import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class ScaleTest {
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    static void delete(Path root)throws Exception{
        try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
    static Entry[][] samples(){
        Entry[][] a=new Entry[16][256];
        var grass=new FeatureDictionary.Feature("minecraft:grass_block","minecraft:plains",FeatureDictionary.Terrain.GRASS);
        var water=new FeatureDictionary.Feature("minecraft:water","minecraft:river",FeatureDictionary.Terrain.WATER);
        Random random=new Random(14);
        for(int i=0;i<a.length;i++)for(int j=0;j<256;j++){
            boolean wet=(j%16+i)%13==0;
            int h=wet?62:64+random.nextInt(40);
            a[i][j]=Entry.leaf(wet?water:grass,h,wet?h-6:h,wet?6:0,
                10000+random.nextInt(1024),20000+random.nextInt(1024),10000+random.nextInt(1024));
        }
        return a;
    }
    static void run(Path root,int edge,int batch,Entry[][] samples)throws Exception{
        var store=new RegionPageStore(root,128,16L*1024*1024);
        var pyramid=new DiskGeographicPyramid(store,10);
        var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuStart=os.getProcessCpuTime(),start=System.nanoTime();int chunks=0;
        var pending=new ArrayList<DiskGeographicPyramid.ChunkSnapshot>();
        // Spatially ordered 4x4 groups: mirrors a region-file discovery iterator.
        int tile=batch>=256?16:batch>=64?8:4;
        tile=Math.min(tile,edge);
        for(int tz=0;tz<edge;tz+=tile)for(int tx=0;tx<edge;tx+=tile)
          for(int gz=tz;gz<Math.min(tz+tile,edge);gz+=4)for(int gx=tx;gx<Math.min(tx+tile,edge);gx+=4)
            for(int dz=0;dz<4;dz++)for(int dx=0;dx<4;dx++){
                pending.add(new DiskGeographicPyramid.ChunkSnapshot(gx+dx-edge/2,gz+dz-edge/2,samples[(dz*4+dx)%16]));
                chunks++;
                if(pending.size()==batch){pyramid.updateChunks(pending);pending.clear();}
            }
        if(!pending.isEmpty())pyramid.updateChunks(pending);
        double seconds=(System.nanoTime()-start)/1e9;
        double cpuSeconds=(os.getProcessCpuTime()-cpuStart)/1e9;
        System.out.printf(Locale.ROOT,"processCpuSeconds=%.3f avgCpuCores=%.2f cpuMicrosPerChunk=%.2f%n",cpuSeconds,cpuSeconds/seconds,cpuSeconds*1e6/chunks);
        long writes=store.diskWrites(),reads=store.diskReads(),flushes=store.durableFlushes();
        long encode=store.encodeNanos(),append=store.appendNanos(),cache=store.cacheNanos();
        long bytes;
        try(var files=Files.walk(root)){bytes=files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum();}
        check(store.estimatedCacheBytes()<=16L*1024*1024,"bounded cache");
        long reopenStart=System.nanoTime();
        store=new RegionPageStore(root,128,16L*1024*1024);
        double reopenMs=(System.nanoTime()-reopenStart)/1e6;
        long scanBytes=store.startupScanBytes();
        pyramid=new DiskGeographicPyramid(store,10);
        long coverage=0;
        int min=Math.floorDiv(-edge/2*16,1024),max=Math.floorDiv((edge/2)*16-1,1024);
        Set<Key> overviewPages=new HashSet<>();
        for(int z=min;z<=max;z++)for(int x=min;x<=max;x++){
            var cell=new GeographicPyramid.Cell(10,x,z);
            var value=pyramid.get(cell);
            check(value!=null,"overview cell");
            coverage+=value.stats().known();
            overviewPages.add(Key.of(cell));
        }
        check(coverage==chunks*256L,"whole world coverage");
        check(store.diskReads()==overviewPages.size(),"only coarse pages after restart");
        long w=store.diskWrites();
        check(pyramid.updateChunk(-edge/2,-edge/2,samples[0])==0&&store.diskWrites()==w,"unchanged chunk");
        System.out.printf(Locale.ROOT,"encodeMs=%.1f appendMs=%.1f cacheMs=%.1f%n",encode/1e6,append/1e6,cache/1e6);
        System.out.printf(Locale.ROOT,"chunks=%d batch=%d seconds=%.3f chunks/s=%.1f pageWrites=%d pageReads=%d diskBytes=%d flushes=%d reopenMs=%.1f startupScanBytes=%d coldOverviewReads=%d coverage=%d%n",
            chunks,batch,seconds,chunks/seconds,writes,reads,bytes,flushes,reopenMs,scanBytes,overviewPages.size(),coverage);
    }
    public static void main(String[] args)throws Exception{
        int edge=Integer.parseInt(args[0]),batch=Integer.parseInt(args[1]);
        if(edge<4||edge>1024||edge%4!=0)throw new IllegalArgumentException("edge");
        Path root=Files.createTempDirectory("cartograph-v4-scale-");
        try{run(root,edge,batch,samples());}finally{delete(root);}
    }
}
