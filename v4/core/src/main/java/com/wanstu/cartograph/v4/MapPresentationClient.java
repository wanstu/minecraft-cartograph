package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Render-thread-owned, nonblocking CPU page residency. No world or filesystem calls. */
public final class MapPresentationClient implements AutoCloseable {
    private static final class Pending{
        final CompletableFuture<MapRaster> future;
        boolean obsolete;
        Pending(CompletableFuture<MapRaster> future){this.future=future;}
    }
    public record Metrics(int resident,int pending,long requests,long accepted,long discarded,long failures){}
    private final PresentationChanges changes;
    private final Function<Key,CompletableFuture<MapRaster>> source;
    private final int capacity,concurrency;
    private final LinkedHashMap<Key,MapRaster> cache=new LinkedHashMap<>();
    private final Map<Key,Pending> pending=new HashMap<>();
    private final Map<Key,Long> retry=new HashMap<>();
    private long seen,requests,accepted,discarded,failures,frame,revision;
    private boolean closed;
    private final Thread owner=Thread.currentThread();
    public MapPresentationClient(PresentationChanges changes,Function<Key,CompletableFuture<MapRaster>> source,int capacity,int concurrency){
        this.changes=Objects.requireNonNull(changes);this.source=Objects.requireNonNull(source);
        if(capacity<1||concurrency<1||concurrency>capacity)throw new IllegalArgumentException("Presentation budget");
        this.capacity=capacity;this.concurrency=concurrency;seen=changes.sequence();
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Presentation owner");}
    private void synchronizeChanges(){
        var delta=changes.since(seen);seen=delta.sequence();
        if(delta.reset()){
            if(!cache.isEmpty())revision++;cache.clear();retry.clear();for(Pending p:pending.values())p.obsolete=true;
        }else for(Key key:delta.keys()){
            if(cache.remove(key)!=null)revision++;retry.remove(key);Pending p=pending.get(key);if(p!=null)p.obsolete=true;
        }
    }
    private void touch(Key key){var r=cache.get(key);if(r!=null)cache.putLast(key,r);}
    /** Only plan requests. Previously resident pages remain available across pans and styles. */
    public void tick(List<Key> wanted){
        owner();if(closed)throw new IllegalStateException("Presentation closed");if(wanted.size()>capacity)throw new IllegalArgumentException("View exceeds residency");
        frame++;synchronizeChanges();
        var desired=new HashSet<>(wanted);
        retry.keySet().removeIf(k->!desired.contains(k));
        for(Key key:wanted)touch(key);
        var it=pending.entrySet().iterator();
        while(it.hasNext()){
            var item=it.next();Pending p=item.getValue();Key key=item.getKey();
            if(!p.future.isDone())continue;
            it.remove();
            if(p.obsolete||!desired.contains(key)){discarded++;continue;}
            try{
                MapRaster raster=p.future.join();
                if(raster==null||!key.equals(raster.key()))throw new IllegalStateException("Mismatched page");
                while(cache.size()>=capacity){var oldest=cache.keySet().iterator();oldest.next();oldest.remove();}
                cache.put(key,raster);retry.remove(key);accepted++;revision++;
            }catch(CompletionException|IllegalStateException e){failures++;retry.put(key,frame+30);}
        }
        // Protect visible resident pages from eviction by late arrivals.
        for(Key key:wanted)touch(key);
        for(Key key:wanted){
            if(pending.size()>=concurrency)break;
            if(cache.containsKey(key)||pending.containsKey(key)||retry.getOrDefault(key,0L)>frame)continue;
            try{pending.put(key,new Pending(Objects.requireNonNull(source.apply(key))));requests++;}
            catch(RuntimeException e){failures++;retry.put(key,frame+30);}
        }
    }
    public MapRaster get(Key key){owner();return cache.get(key);}
    /** Include resident authoritative empty pages in sparse fallback branches. */
    public void addEmptyKeys(Set<Key> keys){
        owner();
        for(var e:cache.entrySet())if(e.getValue().empty())keys.add(e.getKey());
    }
    public long revision(){owner();return revision;}
    public Metrics metrics(){owner();return new Metrics(cache.size(),pending.size(),requests,accepted,discarded,failures);}
    @Override public void close(){
        owner();if(closed)return;closed=true;
        // Drop references immediately; the shared IO owner may finish its read jobs.
        // Do not interrupt the worker or cancel durable operations.
        discarded+=pending.size();pending.clear();cache.clear();retry.clear();revision++;
    }
}
