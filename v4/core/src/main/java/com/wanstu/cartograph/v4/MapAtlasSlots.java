package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** One spatial slot per page generation; independent residency bits for each style plane. */
public final class MapAtlasSlots {
    public record Page(Key key,long generation){}
    private record Resident(int slot,int mask){}
    private final LinkedHashMap<Page,Resident> entries=new LinkedHashMap<>();
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private final Map<Key,Page> spatial=new HashMap<>();
    private final int capacity;
    public MapAtlasSlots(int count){
        if(count<1)throw new IllegalArgumentException("Atlas capacity");
        capacity=count;
        for(int i=0;i<count;i++)free.add(i);
    }
    /** Read-only inspection must not turn offscreen entries into recent ones. */
    public void touch(Page page){
        var r=entries.get(page);if(r!=null)entries.putLast(page,r);
    }
    public Integer slot(Page page){var r=entries.get(page);return r==null?null:r.slot;}
    public boolean ready(Page page,MapRaster.Style style){
        var r=entries.get(page);return r!=null&&(r.mask&(1<<style.ordinal()))!=0;
    }
    /** Reserve an address, but do not publish any style until upload succeeds. */
    public Integer reserve(Page page,Set<Page> protectedPages){
        return reserve(page,protectedPages,false);
    }
    /** Stage a new relief revision without overwriting a still-drawn texture. */
    public Integer reserveStaged(Page page,Set<Page> protectedPages){return reserve(page,protectedPages,true);}
    private Integer reserve(Page page,Set<Page> protectedPages,boolean stage){
        var existing=entries.get(page);if(existing!=null){touch(page);return existing.slot;}
        Page old=spatial.get(page.key());
        Integer slot=null;
        if(old!=null){
            // In-place generation replacement only if the previous generation is not
            // protected by the current draw plan. All plane bits start invalid.
            if(protectedPages.contains(old)){if(!stage)return null;}
            else{slot=entries.remove(old).slot;spatial.remove(old.key(),old);}
        }
        if(slot==null)slot=free.pollFirst();
        if(slot==null){
            var it=entries.entrySet().iterator();
            while(it.hasNext()){
                var e=it.next();
                if(!protectedPages.contains(e.getKey())){slot=e.getValue().slot;spatial.remove(e.getKey().key(),e.getKey());it.remove();break;}
            }
        }
        if(slot!=null){entries.put(page,new Resident(slot,0));spatial.put(page.key(),page);}
        return slot;
    }
    public void uploaded(Page page,int slot,MapRaster.Style style){
        var r=entries.get(page);
        if(r==null||r.slot!=slot)throw new IllegalStateException("Stale atlas upload");
        entries.putLast(page,new Resident(slot,r.mask|1<<style.ordinal()));
    }
    /** Discard a whole plane's validity before releasing its backing texture. */
    public void clearStyle(MapRaster.Style style){
        int bit=1<<style.ordinal();
        entries.replaceAll((k,r)->new Resident(r.slot,r.mask&~bit));
    }
    /** A newly confirmed empty page supersedes every staged/visible revision. */
    public void discard(Key key){
        var it=entries.entrySet().iterator();
        while(it.hasNext()){
            var e=it.next();
            if(!e.getKey().key().equals(key))continue;
            free.addLast(e.getValue().slot);it.remove();
        }
        spatial.remove(key);
    }
    public void clear(){entries.clear();spatial.clear();free.clear();for(int i=0;i<capacity;i++)free.add(i);}
    public int size(){return entries.size();}
}
