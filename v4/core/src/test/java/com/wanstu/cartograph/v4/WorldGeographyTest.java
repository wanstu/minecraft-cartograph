package com.wanstu.cartograph.v4;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.GeographicPyramid.*;
public final class WorldGeographyTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-world-residency-");
        try{
            var f=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
            Entry a=Entry.leaf(f,70,68,0,100,200,300),b=Entry.leaf(f,90,88,0,100,200,300);
            Entry[] first=new Entry[256],second=new Entry[256];Arrays.fill(first,a);Arrays.fill(second,b);
            var ca=FreshRegionBuilder.Columns.fromEntries(first);
            var left=new RegionShard.Id(-2,-1);var right=new RegionShard.Id(-1,-1);
            var world=new WorldGeography(root,2,8,4L*1024*1024,2L*1024*1024);
            Key coarse=new Key(10,-1,-1),leftLeaf=Key.of(new Cell(0,-2048,-1024)),rightLeaf=Key.of(new Cell(0,-1024,-1024));
            var absent=world.read(coarse);
            check(absent.data().size()==0&&world.metrics().publishedShards()==0,"unknown is empty");
            world.buildFresh(left,(x,z)->x==-128&&z==-64?ca:null);
            world.buildFresh(right,(x,z)->x==-64&&z==-64?ca:null);
            var overview=world.read(coarse);
            check(overview.generation()!=absent.generation(),"new shard invalidates overview");
            check(overview.data().size()==2&&overview.data().entryAt(30+31*32).stats().known()==256&&overview.data().entryAt(31+31*32).stats().known()==256,"cross-shard overview");
            var l=world.read(leftLeaf);var r=world.read(rightLeaf);
            var before=world.metrics();
            for(int i=0;i<1000;i++){
                check(world.read(coarse)==overview&&world.read(leftLeaf)==l&&world.read(rightLeaf)==r,"cached immutable page");
            }
            var after=world.metrics();
            check(after.assemblies()==before.assemblies()&&after.sourceReads()==before.sourceReads()&&after.pageHits()-before.pageHits()==3000,"no repeated IO or assembly");
            check(world.updateChunks(right,List.of(new DiskGeographicPyramid.ChunkSnapshot(-64,-64,first)))==0,"no-op write");
            check(world.read(coarse)==overview&&world.read(rightLeaf)==r,"no-op preserves upload token");
            check(world.updateChunks(right,List.of(new DiskGeographicPyramid.ChunkSnapshot(-64,-64,second)))>0,"changed write");
            check(world.read(leftLeaf)==l,"unrelated page preserved");
            check(world.read(rightLeaf).data().entryAt(0).equals(b),"updated leaf");
            check(world.read(coarse).generation()!=overview.generation(),"updated ancestor");
            check(world.read(coarse).data().entryAt(31+31*32).stats().surfaceSum()==90L*256,"updated coarse");
            // Unknown render reads stay read-only and cache remains bounded.
            for(int i=0;i<32;i++)world.read(new Key(10,1000+i,1000));
            check(world.metrics().residentPages()<=8&&world.metrics().residentShards()<=2&&world.metrics().residentPageBytes()<=4L*1024*1024,"budgets");
            try(var dirs=Files.list(root)){check(dirs.count()==2,"no directories from unknown reads");}
            var reopened=new WorldGeography(root,1,4,2L*1024*1024,1024*1024);
            check(reopened.read(coarse).data().entryAt(31+31*32).stats().surfaceSum()==90L*256,"reopen after changes");
            check(reopened.metrics().residentShards()==1,"shard eviction");
            System.out.println("PASS: cross-shard overview, 3000 unchanged reads without IO/assembly, no-op upload reuse, local invalidation, unknown read-only, bounded caches, reopen");
        }finally{ScaleTest.delete(root);}
    }
}
