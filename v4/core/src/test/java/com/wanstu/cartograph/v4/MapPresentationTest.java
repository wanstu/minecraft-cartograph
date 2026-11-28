package com.wanstu.cartograph.v4;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
public final class MapPresentationTest {
    static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
    static MapRaster raster(Key key,long generation){
        var feature=new FeatureDictionary.Feature("minecraft:grass_block","minecraft:plains",FeatureDictionary.Terrain.GRASS);
        var leaf=Entry.leaf(feature,72,70,0,65535,0,0);
        Entry sample=key.lod()==0?leaf:new Entry(leaf.stats(),null);
        return MapRaster.build(new WorldGeography.RenderPage(generation,new Page(key,generation,Map.of(0,sample))));
    }
    public static void main(String[] args){
        var plan=MapViewport.plan(-17,-35,0.25,1920,1080,384);
        check(plan.keys().size()<=384&&plan.keys().stream().anyMatch(k->k.x()<0&&k.z()<0),"negative world view");
        var a=new Key(0,-1,0);var b=new Key(0,0,0);var c=new Key(0,1,0);
        var feed=new PresentationChanges();
        Map<Key,CompletableFuture<MapRaster>> work=new HashMap<>();
        var client=new MapPresentationClient(feed,k->{var f=new CompletableFuture<MapRaster>();work.put(k,f);return f;},8,2);
        client.tick(List.of(a,b,c));check(client.metrics().pending()==2,"bounded requests");
        work.get(a).complete(raster(a,1));work.get(b).complete(raster(b,2));
        client.tick(List.of(a,b,c));check(client.get(a)!=null&&client.get(b)!=null,"nonblocking arrivals");
        var first=client.get(a);long requests=client.metrics().requests();
        check(first.argb(MapRaster.Style.SATELLITE_MODERN,0)==0xffff0000,"linear RGB");
        check(first.argb(MapRaster.Style.HAND_MODERN,0)!=first.argb(MapRaster.Style.SATELLITE_MODERN,0),"independent hand presentation");
        check(first.coverage(1)==0&&first.argb(MapRaster.Style.HAND_ANCIENT,1)==0&&Float.isNaN(first.surface(1)),"unknown transparent");
        check(first.surface(0)==72&&first.ground(0)==70,"height channels");
        check(raster(new Key(3,0,0),1).coverage(0)==4,"partial coverage");
        for(int i=0;i<1000;i++)client.tick(List.of(a,b));
        check(client.get(a)==first&&client.metrics().requests()==requests,"warm idle no requests");
        feed.changed(List.of(c));
        work.get(c).complete(raster(c,3));
        client.tick(List.of(a,b,c));check(client.get(c)==null&&client.get(a)==first,"stale in-flight rejected");
        work.get(c).complete(raster(c,4));client.tick(List.of(a,b,c));check(client.get(c).generation()==4,"fresh response");
        feed.changed(List.of(b));client.tick(List.of(a,b,c));check(client.get(b)==null&&client.get(a)==first,"local invalidation");
        work.get(b).complete(raster(b,5));client.tick(List.of(a,b,c));
        client.tick(List.of(c));client.tick(List.of(a,b,c));check(client.get(a)==first,"pan away and back reuse");
        long before=feed.sequence();for(int i=0;i<9000;i++)feed.changed(List.of(c));
        check(feed.since(before).reset(),"bounded journal overflow resets");
        client.tick(List.of(a));check(client.get(a)==null,"reset evicts stale cache");
        var small=new MapPresentationClient(new PresentationChanges(),k->CompletableFuture.completedFuture(raster(k,k.x()+10)),2,1);
        small.tick(List.of(a));small.tick(List.of(a));small.tick(List.of(b));small.tick(List.of(b));
        for(int i=0;i<1000;i++){check(small.get(a)!=null,"inspection");}
        small.tick(List.of(b,c));small.tick(List.of(b,c));
        check(small.get(a)==null&&small.get(b)!=null&&small.get(c)!=null,"inspection never promotes offscreen CPU page");
        // Repeated session close must drop resident data and pending references
        // even if an asynchronous read completes after release.
        for(int i=0;i<1000;i++){
            var late=new CompletableFuture<MapRaster>();
            var closing=new MapPresentationClient(new PresentationChanges(),k->k.equals(a)?CompletableFuture.completedFuture(raster(a,1)):late,2,2);
            closing.tick(List.of(a,b));closing.tick(List.of(a,b));
            check(closing.metrics().resident()==1&&closing.metrics().pending()==1,"close fixture");
            closing.close();closing.close();
            check(closing.metrics().resident()==0&&closing.metrics().pending()==0&&closing.get(a)==null,"released presentation");
            late.complete(raster(b,2));
            check(closing.get(b)==null&&closing.metrics().resident()==0,"late result not retained");
            try{closing.tick(List.of(a));throw new AssertionError("closed presentation reused");}catch(IllegalStateException expected){}
        }
        var water=new FeatureDictionary.Feature("minecraft:water[level=0]","minecraft:ocean",FeatureDictionary.Terrain.WATER);
        var shallow=Entry.leaf(water,62,61,1,20000,30000,65535);
        var deep=Entry.leaf(water,62,42,20,20000,30000,65535);
        var wet=MapRaster.build(new WorldGeography.RenderPage(20,new Page(a,20,Map.of(0,shallow,1,deep))));
        for(var style:MapRaster.Style.values()){
            check((wet.argb(style,1)&255)<(wet.argb(style,0)&255),"observed water depth darker in every style");
            check(wet.coverage(0)==255&&wet.coverage(1)==255,"depth does not change coverage");
        }
        System.out.println("PASS: bounded view, independent palettes/height/coverage, async cap, 1000 idle frames no requests, stale completion, local invalidation, pan reuse and journal overflow");
    }
}
