package com.wanstu.cartograph.v4;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class WorkerTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    static void await(CountDownLatch latch){
        try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    static void run(Path root)throws Exception{
        var f=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        var a=Entry.leaf(f,70,70,0,100,200,300);
        var b=Entry.leaf(f,90,90,0,200,300,400);
        Entry[] first=new Entry[256],latest=new Entry[256];Arrays.fill(first,a);Arrays.fill(latest,b);
        CountDownLatch gate=new CountDownLatch(1);
        var worker=new GeographicWorker(root,10,8,4096,2,2,()->await(gate));
        Key k0=new Key(0,0,0),k1=new Key(1,0,0),k2=new Key(2,0,0),k3=new Key(3,0,0);
        var obsolete=worker.request(k0,GeographicWorker.Priority.PREFETCH);
        var shared=worker.request(k1,GeographicWorker.Priority.PREFETCH);
        var promoted=worker.request(k1,GeographicWorker.Priority.VISIBLE);
        check(worker.metrics().queuedVisible()==1&&worker.metrics().queuedPrefetch()==1,"promotion and read dedup");
        var visible=worker.request(k2,GeographicWorker.Priority.VISIBLE);
        check(obsolete.isCompletedExceptionally(),"visible preempts prefetch");
        check(worker.request(k3,GeographicWorker.Priority.VISIBLE).isCompletedExceptionally(),"bounded reads");
        worker.retainReads(Set.of(k1));
        check(visible.isCompletedExceptionally(),"viewport cancellation");
        var oldWrite=worker.submitChunk(0,0,first);
        var newWrite=worker.submitChunk(0,0,latest);
        check(oldWrite.join()==GeographicWorker.WriteResult.SUPERSEDED&&!newWrite.isDone(),"coalesced receipt");
        check(worker.offerChunk(4,4,first),"second chunk");
        check(!worker.offerChunk(8,8,first),"bounded writes explicit retry");
        latest[0]=a; // submission owns its immutable copy
        check(worker.metrics().coalescedWrites()==1&&worker.metrics().queuedWrites()==2,"write dedup");
        gate.countDown();
        worker.ready().get(15,TimeUnit.SECONDS);
        check(shared.get(15,TimeUnit.SECONDS).equals(promoted.get(15,TimeUnit.SECONDS)),"shared read result");
        worker.closeAsync().get(30,TimeUnit.SECONDS);
        check(newWrite.join()==GeographicWorker.WriteResult.COMMITTED,"durable receipt");
        var m=worker.metrics();
        check(m.committedChunks()==2&&m.diskWrites()<22&&!m.failed(),"drain writes");
        check(m.estimatedCacheBytes()<=4096,"cache budget");
        check(!worker.offerChunk(0,0,first),"closed rejects");
        var store=new RegionPageStore(root,8,4096);
        var p=new DiskGeographicPyramid(store,10);
        check(p.get(new GeographicPyramid.Cell(0,0,0)).equals(b),"latest snapshot durable");
        check(p.get(new GeographicPyramid.Cell(0,64,64)).equals(a),"second durable");
        check(p.get(new GeographicPyramid.Cell(0,128,128))==null,"rejected not silently written");
        check(store.estimatedCacheBytes()<=4096,"oversized page bypass");
        store.clearCache();check(store.estimatedCacheBytes()==0,"clear budget");
        // Restart and obtain only the coarse page, no child reads.
        var reopen=new GeographicWorker(root,10,8,4096,2,2);
        reopen.ready().get(15,TimeUnit.SECONDS);
        check(reopen.request(new Key(10,0,0),GeographicWorker.Priority.VISIBLE).get(15,TimeUnit.SECONDS).entries().get(0).stats().known()==512,"coarse reopen");
        reopen.closeAsync().get(15,TimeUnit.SECONDS);
        check(reopen.metrics().diskReads()==1,"coarse direct disk read");
        // Fail closed if a corrupt journal is found; no claims of durability.
        Files.write(root.resolve("pages.cv4log"),new byte[]{1,2,3,4,5,6,7,8},StandardOpenOption.APPEND);
        var fail=new GeographicWorker(root,10,8,4096,2,2);
        try{fail.ready().get(15,TimeUnit.SECONDS);throw new AssertionError("failure");}catch(ExecutionException expected){}
        check(fail.metrics().failed()&&!fail.offerChunk(0,0,first),"fail closed");
        System.out.println("PASS: priority, read dedup, viewport cancellation, bounded queues, latest snapshot, drain/restart, byte budget, fail closed");
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-v4-worker-");
        try{run(root);}finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
