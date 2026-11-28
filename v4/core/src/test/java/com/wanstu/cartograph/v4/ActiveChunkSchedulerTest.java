package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.CapturePump.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Entry;

public final class ActiveChunkSchedulerTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    public static void main(String[] args){
        var f=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        var leaf=Entry.leaf(f,80,80,0,100,200,300);
        int[] starts={0};
        var scheduler=new ActiveChunkScheduler(t->{
            starts[0]++;
            return new Capture(){
                public Entry column(int index){return leaf;}
                public boolean valid(){return true;}
            };
        },100,10,2);
        scheduler.loaded(0,0,false);
        scheduler.loaded(-9,4,true);
        scheduler.loaded(2000,-1000,true);
        for(int i=0;i<100000;i++)scheduler.changed(-9,4);
        check(scheduler.metrics().active()==3&&scheduler.metrics().queuedDirty()==2,"coalesced active events");
        var first=scheduler.poll();
        check(first.chunkX()==-9,"dirty first");
        var capture=scheduler.begin(first);
        scheduler.changed(-9,4);
        check(!capture.valid(),"mutation invalidates partial capture");
        scheduler.finish(first,Outcome.RETRY);
        var second=scheduler.poll();
        check(second.chunkX()==2000,"remote loader active");
        scheduler.finish(second,Outcome.COMMITTED);
        check(scheduler.poll()==null,"no tight retry");
        scheduler.advance(10);
        var review=scheduler.poll();
        check(review.chunkX()==0,"review fairness");
        scheduler.finish(review,Outcome.COMMITTED);
        var retry=scheduler.poll();check(retry.chunkX()==-9,"dirty retry");
        scheduler.unloaded(-9,4);
        check(scheduler.metrics().inactivePending()==1,"unload retains pending work");
        scheduler.finish(retry,Outcome.COMMITTED);
        check(scheduler.metrics().inactivePending()==1&&scheduler.metrics().staleReceipts()==1,"stale receipt cannot clear unload");
        check(scheduler.drainInactive(1,d->false)==0&&scheduler.metrics().inactivePending()==1,"handoff backpressure");
        scheduler.loaded(-9,4,false);
        check(scheduler.metrics().inactivePending()==0,"reload inherits dirty");
        var reloaded=scheduler.poll();
        check(reloaded.chunkX()==-9&&!reloaded.equals(retry),"new reservation");
        scheduler.finish(reloaded,Outcome.COMMITTED);
        scheduler.unloaded(-9,4);
        check(scheduler.metrics().inactivePending()==0,"clean unload no recapture");
        scheduler.changed(12345,-12345);
        List<ActiveChunkScheduler.InactiveDirty> handoff=new ArrayList<>();
        check(scheduler.drainInactive(1,d->{handoff.add(d);return true;})==1&&handoff.get(0).position().x()==12345,"saved path");
        check(scheduler.poll()==null,"clean chunks not rescanned each tick");
        scheduler.advance(110);
        check(scheduler.poll()!=null,"periodic fallback");
        // Actual capture pump integration: stale durable receipt after mutation must leave newer work.
        var live=new ActiveChunkScheduler(t->new Capture(){
            public Entry column(int i){return leaf;}
            public boolean valid(){return true;}
        },1000,10,8);
        live.loaded(10,20,true);
        List<CompletableFuture<GeographicWorker.WriteResult>> receipts=new ArrayList<>();
        var pump=new CapturePump(live,(x,z,data)->{
            var receipt=new CompletableFuture<GeographicWorker.WriteResult>();receipts.add(receipt);return receipt;
        },1);
        pump.tick(1_000_000_000L,256,1);
        check(receipts.size()==1,"full capture");
        live.changed(10,20);receipts.get(0).complete(GeographicWorker.WriteResult.COMMITTED);
        pump.tick(1_000_000_000L,256,1);
        check(live.metrics().queuedDirty()==1&&receipts.size()==1,"old commit leaves dirty and cooldown");
        live.advance(10);pump.tick(1_000_000_000L,256,1);
        check(receipts.size()==2,"new revision captured");
        receipts.get(1).complete(GeographicWorker.WriteResult.COMMITTED);
        pump.tick(1_000_000_000L,256,1);
        check(live.metrics().queuedReview()==1&&live.metrics().queuedDirty()==0,"durable clean");
        // Rejected old snapshots must never be submitted after mutation or unload/reload.
        for(boolean unload:new boolean[]{false,true}){
            var scheduler2=new ActiveChunkScheduler(t->new Capture(){
                public Entry column(int i){return leaf;}
                public boolean valid(){return true;}
            },1000,10,8);
            scheduler2.loaded(3,4,true);
            List<CompletableFuture<GeographicWorker.WriteResult>> submitted=new ArrayList<>();
            var pump2=new CapturePump(scheduler2,(x,z,data)->{
                var future=new CompletableFuture<GeographicWorker.WriteResult>();submitted.add(future);return future;
            },1);
            pump2.tick(1_000_000_000L,256,1);
            if(unload){scheduler2.unloaded(3,4);scheduler2.loaded(3,4,false);}
            else scheduler2.changed(3,4);
            submitted.getFirst().completeExceptionally(new RejectedExecutionException("queue full"));
            check(pump2.tick(1_000_000_000L,0,0)==0,"receipt-only tick");
            check(submitted.size()==1&&pump2.metrics().outstanding()==0&&scheduler2.metrics().queuedDirty()==1,"obsolete rejected snapshot not retried");
            scheduler2.advance(10);pump2.tick(1_000_000_000L,256,1);
            check(submitted.size()==2,"fresh revision submitted");
            submitted.getLast().complete(GeographicWorker.WriteResult.COMMITTED);
            pump2.tick(1_000_000_000L,0,0);
            check(scheduler2.metrics().queuedDirty()==0&&scheduler2.metrics().queuedReview()==1,"receipt-only durable ack");
        }
        // First-open priority is independent of viewport, and cannot starve remote active work.
        var focused=new ActiveChunkScheduler(t->null,1000,10,8);
        for(int i=0;i<20;i++)focused.loaded(-100+i,0,true);
        for(int z=-3;z<=3;z++)for(int x=-3;x<=3;x++)focused.loaded(x,z,true);
        focused.focus(new ActiveChunkScheduler.Position(0,0));
        for(int i=0;i<20;i++){
            var near=focused.poll();
            check(Math.abs(near.chunkX())<=3&&Math.abs(near.chunkZ())<=3,"bounded local priority");
            if(i==0)check(near.chunkX()==0&&near.chunkZ()==0,"player chunk first");
            focused.finish(near,Outcome.COMMITTED);
            var remote=focused.poll();
            check(remote.chunkX()==-100+i,"global age order remains fair");
            focused.finish(remote,Outcome.COMMITTED);
        }
        focused.focus(new ActiveChunkScheduler.Position(Integer.MAX_VALUE,Integer.MAX_VALUE));
        focused.loaded(Integer.MIN_VALUE,Integer.MIN_VALUE,true);
        var global=focused.poll();check(global.chunkX()==Integer.MIN_VALUE,"no coordinate wrap");
        focused.finish(global,Outcome.COMMITTED);
        focused.focus(null);
        while((global=focused.poll())!=null)focused.finish(global,Outcome.COMMITTED);
        check(focused.metrics().queuedDirty()==0&&focused.metrics().queuedReview()==70,"all active work still completed");
        // Loaded chunk count does not create a per-tick scan or a queue entry per event.
        var large=new ActiveChunkScheduler(t->null,12000,20,8);
        for(int i=0;i<50000;i++)large.loaded(i,0,false);
        for(int i=0;i<100000;i++)check(large.poll()==null,"idle scheduler");
        check(large.metrics().queuedReview()==50000&&large.metrics().reserved()==0,"one review per active chunk");
        for(int i=0;i<50000;i++)large.unloaded(i,0);
        check(large.metrics().active()==0&&large.metrics().queuedReview()==0&&large.metrics().inactivePending()==0,"clean unload frees all active records");
        System.out.println("PASS: active lifecycle, remote loader, 100000 event coalescing, dirty priority/review fairness, stale capture and receipt, unload handoff/backpressure, reload, 50000 active lifecycle and capture pump integration");
    }
}
