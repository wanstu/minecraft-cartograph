package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.CapturePump.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Entry;

public final class CapturePumpTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        var q=new ArrayDeque<Ticket>();
        var outcomes=new HashMap<Ticket,Outcome>();
        var f=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        var leaf=Entry.leaf(f,80,80,0,1,2,3);
        var first=new Ticket(-5,7,1);var second=new Ticket(1000,-1000,1);var third=new Ticket(2000,3000,1);
        q.addAll(List.of(first,second,third));
        List<CompletableFuture<GeographicWorker.WriteResult>> writes=new ArrayList<>();
        Source source=new Source(){
            public Ticket poll(){return q.poll();}
            public Capture begin(Ticket t){return new Capture(){
                int seen;
                public boolean valid(){return !t.equals(third)||seen<3;}
                public Entry column(int index){seen++;return leaf;}
            };}
            public void finish(Ticket t,Outcome o){outcomes.put(t,o);}
        };
        var pump=new CapturePump(source,(x,z,a)->{
            check(a.length==256&&a[255].equals(leaf),"complete snapshot");
            var result=new CompletableFuture<GeographicWorker.WriteResult>();writes.add(result);return result;
        },1);
        for(int i=0;i<4;i++)check(pump.tick(1_000_000_000L,64,1)==64,"64-column limit");
        check(writes.size()==1&&outcomes.isEmpty(),"not durable yet");
        check(pump.tick(1_000_000_000L,64,1)==0,"backpressure");
        writes.get(0).completeExceptionally(new RejectedExecutionException("queue full"));
        check(pump.tick(1_000_000_000L,0,0)==0&&writes.size()==1,"receipt-only pressure defers retry");
        check(pump.tick(1_000_000_000L,64,1)==0,"retry without resampling");
        check(outcomes.isEmpty()&&writes.size()==2&&pump.metrics().sampledColumns()==256,"rejected snapshot retained");
        writes.get(1).complete(GeographicWorker.WriteResult.COMMITTED);
        for(int i=0;i<4;i++)pump.tick(1_000_000_000L,64,1);
        check(outcomes.get(first)==Outcome.COMMITTED&&writes.size()==3,"first durable, next captured");
        writes.get(2).complete(GeographicWorker.WriteResult.COMMITTED);
        pump.tick(1_000_000_000L,64,1);
        pump.tick(1_000_000_000L,64,1);
        check(outcomes.get(second)==Outcome.COMMITTED&&outcomes.get(third)==Outcome.RETRY,"commit and stale capture");
        check(pump.metrics().sampledColumns()==576&&pump.metrics().committed()==2,"metrics");
        System.out.println("PASS: viewport-free capture, tick column budget, bounded outstanding, rejection retry, durable ack, stale snapshot rejection");
    }
}
