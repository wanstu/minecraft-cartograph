package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.ActiveChunkScheduler.*;

/** Bounded nonblocking server-to-IO bridge. No internal threads or per-event disk IO. */
public final class DirtyHandoffQueue {
    private record Pending(InactiveDirty dirty,CompletableFuture<SavedDirtyIndex.Work> receipt){}
    private final ArrayDeque<Pending> queue=new ArrayDeque<>();
    private final int capacity;
    private int inFlight;
    private boolean closed;
    public DirtyHandoffQueue(int capacity){if(capacity<1)throw new IllegalArgumentException("Capacity");this.capacity=capacity;}
    public synchronized int size(){return queue.size()+inFlight;}
    public synchronized CompletableFuture<SavedDirtyIndex.Work> submit(InactiveDirty dirty){
        Objects.requireNonNull(dirty);
        if(closed||size()>=capacity)return CompletableFuture.failedFuture(new RejectedExecutionException("Dirty handoff unavailable"));
        var receipt=new CompletableFuture<SavedDirtyIndex.Work>();
        queue.add(new Pending(dirty,receipt));return receipt.copy();
    }
    /** IO owner calls at most once per turn. Durable receipts only after mark returns. */
    public int drain(SavedDirtyIndex index,int max)throws IOException{
        if(max<1||max>256)throw new IllegalArgumentException("Batch");
        List<Pending> batch=new ArrayList<>();
        synchronized(this){
            if(inFlight!=0)throw new IllegalStateException("Concurrent drain");
            while(batch.size()<max&&!queue.isEmpty())batch.add(queue.remove());
            inFlight=batch.size();
        }
        if(batch.isEmpty())return 0;
        try{
            var work=index.mark(batch.stream().map(p->p.dirty.position()).toList());
            for(int i=0;i<batch.size();i++)batch.get(i).receipt.complete(work.get(i));
            return batch.size();
        }catch(IOException|RuntimeException e){
            for(Pending p:batch)p.receipt.completeExceptionally(e);
            throw e;
        }finally{synchronized(this){inFlight=0;}}
    }
    /** Stop accepting; the IO owner may drain already accepted work before shutdown. */
    public synchronized void closeInput(){closed=true;}
}
