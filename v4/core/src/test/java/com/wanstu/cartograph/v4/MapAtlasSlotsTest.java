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
