package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class WorldOverviewTest {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    static WorldGeography open(Path root)throws IOException{return new WorldGeography(root,2,4,2L*1024*1024,2L*1024*1024);}
    static final FeatureDictionary.Feature ROCK=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
    static Entry[] chunk(int y){var c=new Entry[256];Arrays.fill(c,Entry.leaf(ROCK,y,y,0,100,200,300));return c;}
    static long sum(Page p){long n=0;for(var e:p.entries().values())n+=e.stats().surfaceSum();return n;}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-overview-");
        try{
            var w=open(root);var id=new RegionShard.Id(-1,-1);var other=new RegionShard.Id(-2,-1);
            Key overview=new Key(10,-1,-1),neighbor=new Key(10,0,0);
            Path dir=root.resolve("overview-v1"),file=dir.resolve("10.-1.-1.cv4page");
            check(w.read(overview).data().size()==0&&!Files.exists(dir),"unknown read stays read-only");
            var a=chunk(70);var b=chunk(90);var ca=FreshRegionBuilder.Columns.fromEntries(a);
            w.buildFresh(id,(x,z)->x==-64&&z==-64?ca:null);
            var first=w.read(overview);check(sum(first.data())==70L*256&&Files.size(file)>0,"first persist");
            byte[] saved=Files.readAllBytes(file);
            for(int lod=6;lod<10;lod++){
                var k=new Key(lod,-1,-1);check(sum(w.read(k).data())==70L*256,"persist every supported overview LOD");
            }
            w=open(root);var cold=w.read(overview);
            check(cold.generation()!=first.generation()&&cold.data().entries().equals(first.data().entries()),"new generation, identical facts");
            check(w.metrics().overviewHits()==1&&w.metrics().sourceReads()==0&&w.metrics().residentShards()==0&&w.metrics().assemblies()==0,"direct cold overview");
            check(w.updateChunks(id,List.of(new DiskGeographicPyramid.ChunkSnapshot(-64,-64,a)))==0&&Arrays.equals(saved,Files.readAllBytes(file)),"no-op preserves disk cache");
            w.updateChunks(id,List.of(new DiskGeographicPyramid.ChunkSnapshot(-64,-64,b)));
            check(Files.size(file)==0,"invalidated before replacement becomes visible");
            for(int lod=6;lod<10;lod++)check(Files.size(dir.resolve(lod+".-1.-1.cv4page"))==0,"every changed ancestor invalidated");
            w=open(root);check(sum(w.read(overview).data())==90L*256&&w.metrics().overviewHits()==0,"restart after update before render");
            for(int lod=6;lod<10;lod++)check(sum(w.read(new Key(lod,-1,-1)).data())==90L*256,"all LODs recover new facts");
            w.buildFresh(other,(x,z)->x==-128&&z==-64?ca:null);
            check(Files.size(file)==0,"fresh shard invalidates overview");
            w=open(root);check(sum(w.read(overview).data())==160L*256,"cross-shard rebuild");
            var unrelated=new RegionShard.Id(0,0);w.buildFresh(unrelated,(x,z)->x==0&&z==0?ca:null);w.read(neighbor);
            Path nf=dir.resolve("10.0.0.cv4page");byte[] nb=Files.readAllBytes(nf);
            w.updateChunks(id,List.of(new DiskGeographicPyramid.ChunkSnapshot(-64,-64,new Entry[256])));
            check(Files.size(file)==0&&Arrays.equals(nb,Files.readAllBytes(nf)),"local deletion only invalidates affected page");
            w=open(root);check(sum(w.read(overview).data())==70L*256,"deletion persists");
            byte[] corrupt=Files.readAllBytes(file);corrupt[corrupt.length/2]^=1;Files.write(file,corrupt);
            w=open(root);check(sum(w.read(overview).data())==70L*256&&w.metrics().overviewFailures()==1,"corrupt derivative rebuilt from authority");
            w=open(root);check(sum(w.read(overview).data())==70L*256&&w.metrics().overviewHits()==1,"repaired overview reused");
            // Simulated crash between invalidation and source write: existing authoritative data wins.
            new WorldOverviewStore(root).invalidate(overview);
            w=open(root);check(sum(w.read(overview).data())==70L*256&&w.metrics().overviewHits()==0,"interrupted precommit invalidation");
            // Fail before commit, and confirm revision and data remain unchanged.
            var shard=new RegionShard(root,other,4,2L*1024*1024);
            var key=new Key(0,-64,-32);long revision=shard.revision(key);
            try{shard.updateChunks(List.of(new DiskGeographicPyramid.ChunkSnapshot(-128,-64,b)),keys->{throw new IOException("precommit failure");});
                throw new AssertionError("failed invalidation allowed source write");}
            catch(IOException expected){}
            check(shard.revision(key)==revision&&shard.get(new GeographicPyramid.Cell(0,-2048,-1024)).stats().surfaceSum()==70,"failed invalidation preserves source");
            try(var paths=Files.list(dir)){check(paths.noneMatch(p->p.getFileName().toString().startsWith(".overview-")),"no handled temp leak");}
            System.out.println("PASS: persisted cold overview, exact facts, zero shard reads, no-op reuse, localized precommit invalidation, fresh/delete/restart, corrupt derivative recovery, aborted commit");
        }finally{ScaleTest.delete(root);}
    }
}
