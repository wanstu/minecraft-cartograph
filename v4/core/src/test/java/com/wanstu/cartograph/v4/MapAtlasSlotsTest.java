package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;
import static com.wanstu.cartograph.v4.MapRaster.Style.*;

public final class MapAtlasSlotsTest {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static MapAtlasSlots.Page page(int x,long gen){return new MapAtlasSlots.Page(new Key(0,x,0),gen);}
    public static void main(String[] args){
        var atlas=new MapAtlasSlots(2);var a=page(0,1);var b=page(1,1);var c=page(0,2);
        int slot=atlas.reserve(a,Set.of());
        check(!atlas.ready(a,HAND_MODERN),"reservation not published");
        atlas.uploaded(a,slot,SATELLITE_MODERN);
        check(atlas.reserve(a,Set.of())==slot,"stable spatial address");
        check(!atlas.ready(a,HAND_MODERN),"no old style fallback");
        atlas.uploaded(a,slot,HAND_MODERN);
        check(atlas.ready(a,HAND_MODERN)&&atlas.ready(a,SATELLITE_MODERN),"independent style planes");
        atlas.clearStyle(HAND_MODERN);
        check(!atlas.ready(a,HAND_MODERN)&&atlas.ready(a,SATELLITE_MODERN)&&atlas.slot(a)==slot,"plane release retains spatial address");
        int other=atlas.reserve(b,Set.of(a));atlas.uploaded(b,other,HAND_ANCIENT);
        check(atlas.reserve(c,Set.of(a,b))==null,"protected pages never evicted");
        check(atlas.reserve(c,Set.of(b))==slot&&!atlas.ready(c,SATELLITE_MODERN)&&atlas.slot(a)==null,"new generation clears all old style bits");
        try{atlas.uploaded(a,slot,HAND_MODERN);throw new AssertionError("stale upload accepted");}catch(IllegalStateException expected){}
        // Repeated updates must reuse spatial slots even when free slots remain.
        var changing=new MapAtlasSlots(8);
        int stable=changing.reserve(a,Set.of());changing.uploaded(a,stable,SATELLITE_MODERN);
        var patch=new MapTileCoverage.Patch(a.key(),0,0,1,0,0,1);
        var flat=new MapGeometryKey(0,0,0,false,List.of(new MapGeometryKey.Face(a.key(),patch,stable,1)));
        var relief=new MapGeometryKey(0,0,0,true,List.of(new MapGeometryKey.Face(a.key(),patch,stable,1)));
        var last=a;
        try(var buffers=new RetainedGroups<MapGeometryKey,AutoCloseable>(1024,4)){
            check(buffers.ensure(flat,96,Set.of(flat),k->new RetainedGroups.Item<>(()->{},96)),"first flat upload");
        for(int gen=2;gen<=1000;gen++){
            var updated=page(0,gen);
            check(changing.reserve(updated,Set.of(last))==null,"protected old revision");
            check(changing.reserve(updated,Set.of())==stable&&changing.size()==1,"same slot with free space");
            check(changing.slot(last)==null&&!changing.ready(updated,SATELLITE_MODERN),"previous revision cannot draw");
            changing.uploaded(updated,stable,SATELLITE_MODERN);
            var face=new MapGeometryKey.Face(updated.key(),patch,stable,gen);
            var flatNext=new MapGeometryKey(0,0,0,false,List.of(face));
            check(flat.equals(flatNext),"flat VBO independent of pixel revision");
            check(buffers.ensure(flatNext,96,Set.of(flatNext),k->{throw new AssertionError("unnecessary flat upload");}),"flat buffer reused");
            check(!relief.equals(new MapGeometryKey(0,0,0,true,List.of(face))),"raised VBO revision-sensitive");
            last=updated;
        }
            check(buffers.builds()==1&&buffers.size()==1,"one geometry build across 1000 revisions");
        }
        check(!flat.equals(new MapGeometryKey(0,0,0,false,List.of(new MapGeometryKey.Face(a.key(),patch,stable+1,1)))),"slot change requires rebuild");
        var clipped=new MapTileCoverage.Patch(a.key(),0,0,.5,0,0,.5);
        check(!flat.equals(new MapGeometryKey(0,0,0,false,List.of(new MapGeometryKey.Face(a.key(),clipped,stable,1)))),"patch clipping requires rebuild");
        // Raised geometry must retain the previous texture until the new mesh exists.
        var staging=new MapAtlasSlots(3);
        int oldSlot=staging.reserve(a,Set.of());staging.uploaded(a,oldSlot,SATELLITE_MODERN);
        int stagedSlot=staging.reserveStaged(c,Set.of(a));
        check(stagedSlot!=oldSlot&&staging.ready(a,SATELLITE_MODERN)&&!staging.ready(c,SATELLITE_MODERN),"old remains drawable during stage");
        staging.uploaded(c,stagedSlot,SATELLITE_MODERN);
        check(staging.ready(a,SATELLITE_MODERN)&&staging.ready(c,SATELLITE_MODERN)&&!staging.ready(c,HAND_MODERN),"two revisions only in valid style");
        var d=page(0,3);check(staging.reserveStaged(d,Set.of(a,c))!=null,"third staged revision");
        check(staging.reserveStaged(page(0,4),Set.of(a,c,d))==null,"staging bounded by existing capacity");
        int replacement=staging.reserve(page(0,4),Set.of(c));
        check(staging.slot(c)==stagedSlot&&staging.ready(c,SATELLITE_MODERN)&&!staging.ready(page(0,4),SATELLITE_MODERN),"eviction cannot overwrite protected texture");
        staging.uploaded(page(0,4),replacement,SATELLITE_MODERN);
        staging.clearStyle(SATELLITE_MODERN);
        check(!staging.ready(a,SATELLITE_MODERN)&&!staging.ready(c,SATELLITE_MODERN),"style eviction invalidates both revisions");
        staging.discard(a.key());
        check(staging.size()==0&&staging.slot(a)==null&&staging.slot(c)==null&&staging.slot(d)==null,"known empty removes every staged generation");
        try{staging.uploaded(c,stagedSlot,SATELLITE_MODERN);throw new AssertionError("empty stale upload");}catch(IllegalStateException expected){}
        var reclaimed=new HashSet<Integer>();
        for(int i=0;i<3;i++)reclaimed.add(staging.reserve(page(i+1,5),Set.of()));
        check(reclaimed.size()==3&&!reclaimed.contains(null),"discard reclaims all slots exactly once");
        // Scanning the atlas for fallback/readiness must not destroy the LRU order.
        var pan=new MapAtlasSlots(512);
        var window=new ArrayDeque<MapAtlasSlots.Page>();
        for(int i=0;i<2048;i++){
            var p=page(i,1);
            if(i>=512){
                // Non-touching inspection of every resident, including the oldest.
                for(int j=i-1;j>=i-512;j--){var q=page(j,1);check(pan.ready(q,SATELLITE_MODERN)&&pan.slot(q)!=null,"resident prior window");}
            }
            var protectedPages=Set.copyOf(window);
            int id=pan.reserve(p,protectedPages);
            for(var style:MapRaster.Style.values())pan.uploaded(p,id,style);
            if(i>=512)check(pan.slot(page(i-512,1))==null,"evict oldest not inspected page");
            window.addLast(p);if(window.size()>64)window.removeFirst();
            for(var q:window){pan.touch(q);for(var style:MapRaster.Style.values())check(pan.ready(q,style),"visible window remains resident across palettes");}
            check(pan.size()==Math.min(512,i+1),"bounded atlas");
        }
        System.out.println("PASS: 2048-page pan, 512 slots, five palettes, inspection does not change eviction order");
        // Release all capacity, including staged revisions and style bits.
        for(int cycle=0;cycle<100;cycle++){
            var ids=new HashSet<Integer>();
            pan.clear();
            check(pan.size()==0,"atlas release");
            for(int i=0;i<512;i++){
                var p=page(i,cycle+100);
                int id=pan.reserve(p,Set.of());
                check(ids.add(id)&&!pan.ready(p,SATELLITE_MODERN),"unique clean slot");
                pan.uploaded(p,id,SATELLITE_MODERN);
            }
        }
        var random=new Random(1701);var keys=new ArrayList<MapAtlasSlots.Page>();
        for(int i=0;i<31;i++)keys.add(page(i-16,i+1));
        var slots=new MapAtlasSlots(16);
        for(int i=0;i<10000;i++){
            var key=keys.get(random.nextInt(keys.size()));var style=MapRaster.Style.values()[random.nextInt(5)];
            var before=new HashMap<MapAtlasSlots.Page,Integer>();
            for(var k:keys){Integer id=slots.slot(k);if(id!=null)before.put(k,id);}
            if(i%7==0){slots.clearStyle(style);for(var k:keys)check(!slots.ready(k,style),"plane validity cleared");}
            else{
                Integer id=slots.reserve(key,Set.of());
                check(id!=null,"space");
                if(!before.containsKey(key))for(var v:MapRaster.Style.values())check(!slots.ready(key,v),"eviction no stale styles");
                slots.uploaded(key,id,style);
                check(slots.ready(key,style),"upload visible");
            }
            Set<Integer> used=new HashSet<>();
            for(var k:keys){Integer id=slots.slot(k);if(id!=null)check(used.add(id)&&id>=0&&id<16,"unique in-range slots");}
            check(used.size()==slots.size()&&used.size()<=16,"capacity");
        }
        System.out.println("PASS: shared atlas addresses, independent style validity, generation eviction, protected pages, plane release, stale upload and 10000 randomized operations");
    }
}
