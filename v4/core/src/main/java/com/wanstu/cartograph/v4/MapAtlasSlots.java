package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** One spatial slot per page generation; independent residency bits for each style plane. */
public final class MapAtlasSlots {
    public record Page(Key key,long generation){}
    private record Resident(int slot,int mask){}
    private final LinkedHashMap<Page,Resident> entries=new LinkedHashMap<>(16,.75f,true);
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    public MapAtlasSlots(int count){
        if(count<1)throw new IllegalArgumentException("Atlas capacity");
        for(int i=0;i<count;i++)free.add(i);
    }
    public Integer slot(Page page){var r=entries.get(page);return r==null?null:r.slot;}
    public boolean ready(Page page,MapRaster.Style style){
        var r=entries.get(page);return r!=null&&(r.mask&(1<<style.ordinal()))!=0;
    }
    /** Reserve an address, but do not publish any style until upload succeeds. */
    public Integer reserve(Page page,Set<Page> protectedPages){
        var existing=entries.get(page);if(existing!=null)return existing.slot;
        Integer slot=free.pollFirst();
        if(slot==null){
            var it=entries.entrySet().iterator();
            while(it.hasNext()){
                var e=it.next();
                if(!protectedPages.contains(e.getKey())){slot=e.getValue().slot;it.remove();break;}
            }
        }
        if(slot!=null)entries.put(page,new Resident(slot,0));
        return slot;
    }
    public void uploaded(Page page,int slot,MapRaster.Style style){
        var r=entries.get(page);
        if(r==null||r.slot!=slot)throw new IllegalStateException("Stale atlas upload");
        entries.put(page,new Resident(slot,r.mask|1<<style.ordinal()));
    }
    /** Discard a whole plane's validity before releasing its backing texture. */
    public void clearStyle(MapRaster.Style style){
        int bit=1<<style.ordinal();
        entries.replaceAll((k,r)->new Resident(r.slot,r.mask&~bit));
    }
    public int size(){return entries.size();}
}
