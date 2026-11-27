package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Entry;
public final class OfflineRegionBackfillTest{
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-offline-backfill-");
        try{
            Path regions=Files.createDirectory(root.resolve("region")),target=root.resolve("geo");
            byte[] nbt=SavedChunkSurfaceTest.fixture();
            int slot=3*32+30; // chunk -2,3 in region -1,0
            Path file=regions.resolve("r.-1.0.mca");
            int sectors=(nbt.length+5+4095)/4096;
            try(var out=new RandomAccessFile(file.toFile(),"rw")){
                out.setLength((2L+sectors)*4096);
                out.seek(slot*4L);out.writeInt((2<<8)|sectors);
                out.seek(4096+slot*4L);out.writeInt(123);
                out.seek(8192);out.writeInt(nbt.length+1);out.writeByte(3);out.write(nbt);
            }
            var id=new RegionShard.Id(-1,0);
            var mapper=(OfflineRegionBackfill.Mapper)s->{
                Entry[] e=new Entry[256];var cols=s.columns();
                for(int i=0;i<256;i++){
                    var c=cols[i];if(c==null)continue;
                    // Fixture-only explicit color resolver; production registry resolution is separate.
                    var f=new FeatureDictionary.Feature(c.material(),c.biome(),FeatureDictionary.Terrain.OTHER);
                    e[i]=Entry.leaf(f,c.surfaceY(),c.reliefY(),0,1,2,3);
                }
                return FreshRegionBuilder.Columns.fromEntries(e);
            };
            var result=OfflineRegionBackfill.buildShard(regions,target,id,-16,16,mapper);
            check(result.chunks()==1,"observed only");
            var shard=new RegionShard(target,id,8,1024*1024);
            var leaf=shard.get(new GeographicPyramid.Cell(0,-32,48));
            check(leaf.feature().materialKey().equals("minecraft:grass_block[snowy=false]")&&leaf.stats().surfaceSum()==-13,"persisted real schema facts");
            check(shard.get(new GeographicPyramid.Cell(10,-1,0)).stats().known()==255,"exact coverage");
            try{OfflineRegionBackfill.buildShard(regions,target,id,-16,16,mapper);throw new AssertionError("overwrite");}catch(IOException expected){}
            Path failureTarget=root.resolve("failure");
            try{OfflineRegionBackfill.buildShard(regions,failureTarget,id,-16,16,s->{throw new IOException("unmapped state");});throw new AssertionError("mapper failure");}catch(IOException expected){}
            check(!Files.exists(id.directory(failureTarget)),"no partial publication");
            System.out.println("PASS: frozen Anvil -> NBT -> mapped fresh shard -> reopen, exact coverage, no overwrite and mapper failure");
        }finally{ScaleTest.delete(root);}
    }
}
