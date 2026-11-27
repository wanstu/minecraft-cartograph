package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Bounded single-owner IO worker. Callers never wait for IO or perform it themselves. */
public final class GeographicWorker {
    public enum Priority { VISIBLE, PREFETCH }
    public enum WriteResult { COMMITTED, SUPERSEDED }
    private record Chunk(int x,int z) {}
    private record Read(Key key,CompletableFuture<Page> result) {}
    private record Write(Chunk chunk,Entry[] columns,CompletableFuture<WriteResult> receipt) {}
    public record Metrics(int queuedVisible,int queuedPrefetch,int queuedWrites,
                          long coalescedWrites,long rejectedReads,long rejectedWrites,
                          long committedChunks,long diskReads,long diskWrites,
                          long estimatedCacheBytes,boolean failed) {}

    private final Object lock=new Object();
    private final LinkedHashMap<Key,Read> visible=new LinkedHashMap<>(),prefetch=new LinkedHashMap<>();
    private final LinkedHashMap<Chunk,Write> writes=new LinkedHashMap<>();
    private final int readLimit,writeLimit;
    private final Path root;
    private final Runnable beforeOpen;
    private final int pageLimit,maxLod;
    private final long cacheBytes;
    private final CompletableFuture<Void> ready=new CompletableFuture<>(),closed=new CompletableFuture<>();
    private Read activeRead;
    private Write activeWrite;
    private boolean closing;
    private Throwable failure;
    private long coalesced,rejectedReads,rejectedWrites,committed,ioReads,ioWrites,estimatedBytes;

    public GeographicWorker(Path root,int maxLod,int pageLimit,long cacheBytes,int readLimit,int writeLimit) {
        this(root,maxLod,pageLimit,cacheBytes,readLimit,writeLimit,()->{});
    }
    GeographicWorker(Path root,int maxLod,int pageLimit,long cacheBytes,int readLimit,int writeLimit,Runnable beforeOpen) {
        this.beforeOpen=Objects.requireNonNull(beforeOpen);
        this.root=Objects.requireNonNull(root);
        if(maxLod<0||maxLod>10||pageLimit<1||cacheBytes<1||readLimit<1||writeLimit<1)
            throw new IllegalArgumentException("worker limits");
        this.maxLod=maxLod;this.pageLimit=pageLimit;this.cacheBytes=cacheBytes;
        this.readLimit=readLimit;this.writeLimit=writeLimit;
        Thread t=new Thread(this::run,"cartograph-v4-geographic-io");
        t.setDaemon(true);t.start();
    }
    public CompletableFuture<Void> ready(){return ready.copy();}
    public CompletableFuture<Void> closeAsync(){
        List<Read> canceled;
        synchronized(lock){
            closing=true;
            canceled=new ArrayList<>(visible.values());canceled.addAll(prefetch.values());
            visible.clear();prefetch.clear();lock.notifyAll();
        }
        for(Read r:canceled)r.result.cancel(false);
        return closed.copy();
    }
    public Metrics metrics(){
        synchronized(lock){return new Metrics(visible.size(),prefetch.size(),writes.size(),
            coalesced,rejectedReads,rejectedWrites,committed,ioReads,ioWrites,estimatedBytes,failure!=null);}
    }
    /** Identical pending reads share one IO task. Returned futures do not own that task. */
    public CompletableFuture<Page> request(Key key,Priority priority){
        Objects.requireNonNull(key);Objects.requireNonNull(priority);
        if(key.lod()>maxLod)return CompletableFuture.failedFuture(new IllegalArgumentException("LOD"));
        Read evicted=null;
        CompletableFuture<Page> result;
        synchronized(lock){
            if(closing||failure!=null)return CompletableFuture.failedFuture(new IllegalStateException("Worker closed",failure));
            // Reads are snapshots, not a read-after-write barrier.
            if(activeRead!=null&&activeRead.key.equals(key))return activeRead.result.copy();
            Read existing=visible.get(key);
            if(existing!=null)return existing.result.copy();
            existing=prefetch.get(key);
            if(existing!=null){
                if(priority==Priority.VISIBLE){prefetch.remove(key);visible.put(key,existing);}
                return existing.result.copy();
            }
            if(visible.size()+prefetch.size()>=readLimit){
                if(priority==Priority.VISIBLE&&!prefetch.isEmpty())evicted=take(prefetch);
                else{rejectedReads++;return CompletableFuture.failedFuture(new RejectedExecutionException("read queue full"));}
            }
            Read read=new Read(key,new CompletableFuture<>());
            (priority==Priority.VISIBLE?visible:prefetch).put(key,read);
            result=read.result.copy();lock.notifyAll();
        }
        if(evicted!=null)evicted.result.cancel(false);
        return result;
    }
    /** Drop obsolete queued viewport work. Running reads may finish; caller checks viewport revision. */
    public void retainReads(Set<Key> keep){
        Objects.requireNonNull(keep);
        List<Read> canceled=new ArrayList<>();
        synchronized(lock){
            for(var queue:List.of(visible,prefetch)){
                var it=queue.entrySet().iterator();
                while(it.hasNext()){
                    var e=it.next();if(!keep.contains(e.getKey())){canceled.add(e.getValue());it.remove();}
                }
            }
        }
        for(Read r:canceled)r.result.cancel(false);
    }
    /** Nonblocking offer. False means caller MUST keep the chunk dirty and retry. */
    public boolean offerChunk(int chunkX,int chunkZ,Entry[] columns){
        return enqueueChunk(chunkX,chunkZ,columns)!=null;
    }
    /** Only COMMITTED acknowledges persistence; SUPERSEDED means a newer full snapshot replaced it. */
    public CompletableFuture<WriteResult> submitChunk(int chunkX,int chunkZ,Entry[] columns){
        Write write=enqueueChunk(chunkX,chunkZ,columns);
        return write==null?CompletableFuture.failedFuture(new RejectedExecutionException("write unavailable")):write.receipt.copy();
    }
    private Write enqueueChunk(int chunkX,int chunkZ,Entry[] columns){
        Objects.requireNonNull(columns);
        if(columns.length!=256)throw new IllegalArgumentException("chunk");
        Math.multiplyExact(chunkX,16);Math.multiplyExact(chunkZ,16);
        for(Entry e:columns)if(e!=null&&e.feature()==null)throw new IllegalArgumentException("leaf");
        Chunk chunk=new Chunk(chunkX,chunkZ);
        Write previous,next;
        synchronized(lock){
            if(closing||failure!=null){rejectedWrites++;return null;}
            if(!writes.containsKey(chunk)&&writes.size()>=writeLimit){rejectedWrites++;return null;}
            next=new Write(chunk,columns.clone(),new CompletableFuture<>());
            previous=writes.put(chunk,next);
            if(previous!=null)coalesced++;
            lock.notifyAll();
        }
        if(previous!=null)previous.receipt.complete(WriteResult.SUPERSEDED);
        return next;
    }
    private static <K,V> V take(LinkedHashMap<K,V> map){
        var it=map.entrySet().iterator();var e=it.next();V value=e.getValue();it.remove();return value;
    }
    private void run(){
        try{
            beforeOpen.run();
            var store=new RegionPageStore(root,pageLimit,cacheBytes);
            var pyramid=new DiskGeographicPyramid(store,maxLod);
            ready.complete(null);
            int readBurst=0;
            while(true){
                Read read=null;Write write=null;
                synchronized(lock){
                    while(!closing&&visible.isEmpty()&&prefetch.isEmpty()&&writes.isEmpty())lock.wait();
                    if(closing&&writes.isEmpty())break;
                    if(!writes.isEmpty()&&(closing||visible.isEmpty()||readBurst>=4)){
                        write=take(writes);readBurst=0;
                    }else if(!visible.isEmpty()){
                        read=take(visible);readBurst++;
                    }else if(!prefetch.isEmpty()){
                        read=take(prefetch);
                    }
                    activeRead=read;activeWrite=write;
                }
                if(write!=null){
                    pyramid.updateChunk(write.chunk.x,write.chunk.z,write.columns);
                    synchronized(lock){committed++;activeWrite=null;ioReads=store.diskReads();ioWrites=store.diskWrites();estimatedBytes=store.estimatedCacheBytes();}
                    write.receipt.complete(WriteResult.COMMITTED);
                }else if(read!=null){
                    try{
                        Page page=store.read(read.key);
                        synchronized(lock){activeRead=null;ioReads=store.diskReads();ioWrites=store.diskWrites();estimatedBytes=store.estimatedCacheBytes();}
                        read.result.complete(page);
                    }
                    catch(IOException e){read.result.completeExceptionally(e);throw e;}
                }
                synchronized(lock){
                    activeRead=null;ioReads=store.diskReads();ioWrites=store.diskWrites();
                    estimatedBytes=store.estimatedCacheBytes();
                }
            }
            closed.complete(null);
        }catch(Throwable e){
            List<Read> canceled;
            List<Write> unwritten;
            synchronized(lock){
                failure=e;closing=true;
                canceled=new ArrayList<>(visible.values());canceled.addAll(prefetch.values());
                if(activeRead!=null)canceled.add(activeRead);
                visible.clear();prefetch.clear();activeRead=null;
                unwritten=new ArrayList<>(writes.values());writes.clear();
                if(activeWrite!=null)unwritten.add(activeWrite);activeWrite=null;
                // Stop accepting writes, report failure; caller must not treat queued data as durable.
            }
            ready.completeExceptionally(e);
            for(Read r:canceled)r.result.completeExceptionally(e);
            for(Write w:unwritten)w.receipt.completeExceptionally(e);
            closed.completeExceptionally(e);
        }
    }
}
