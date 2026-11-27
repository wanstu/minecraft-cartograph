package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** One bounded IO owner shared across dimensions. No synchronous disk work on caller threads. */
public final class WorldIoService {
    private static final class Dimension{
        final WorldGeography world;final SavedDirtyIndex dirty;ArchiveReconciler archive;
        Dimension(WorldGeography world,SavedDirtyIndex dirty){this.world=world;this.dirty=dirty;}
        void close()throws IOException{if(archive!=null)archive.close();}
    }
    private final Path root;
    private final int dimensionLimit;
    private final Map<String,Dimension> dimensions=new LinkedHashMap<>(16,.75f,true);
    private final ThreadPoolExecutor io;
    private final Map<String,LiveArchiveGate> gates=new ConcurrentHashMap<>();
    private final Map<String,PresentationChanges> presentations=new ConcurrentHashMap<>();
    public record ArchiveProgress(long decoded,long unchanged,long committed,long deferred,long sourceErrors,boolean discovered,int pending){}
    private volatile Throwable failure;
    private volatile Thread ownerThread;
    public WorldIoService(Path root,int dimensionLimit,int queueLimit){
        this.root=Objects.requireNonNull(root);
        if(dimensionLimit<1||queueLimit<1)throw new IllegalArgumentException("IO limits");
        this.dimensionLimit=dimensionLimit;
        io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queueLimit),r->{
            Thread t=new Thread(()->{try{r.run();}finally{
                for(Dimension d:dimensions.values())try{d.close();}catch(IOException e){failure=e;}
                dimensions.clear();
            }},"cartograph-v4-world-io");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);ownerThread=t;return t;
        });
    }
    private static String directory(String dimension){
        if(!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Dimension key");
        return java.util.HexFormat.of().formatHex(dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private Dimension open(String key)throws IOException{
        Dimension d=dimensions.get(key);if(d!=null)return d;
        if(dimensions.size()>=dimensionLimit){
            var it=dimensions.values().iterator();it.next().close();it.remove();
        }
        Path path=root.resolve(directory(key));
        d=new Dimension(new WorldGeography(path,4,32,8L*1024*1024,8L*1024*1024,presentationChanges(key)),new SavedDirtyIndex(path));
        dimensions.put(key,d);return d;
    }
    private <T> CompletableFuture<T> submit(Callable<T> operation){
        var result=new CompletableFuture<T>();
        try{
            io.execute(()->{
                if(failure!=null){result.completeExceptionally(new IOException("World IO failed",failure));return;}
                try{result.complete(operation.call());}
                catch(IOException e){failure=e;result.completeExceptionally(e);}
                catch(Exception e){result.completeExceptionally(e);}
            });
        }catch(RejectedExecutionException e){result.completeExceptionally(e);}
        return result;
    }
    public LiveArchiveGate gate(String dimension){directory(dimension);return gates.computeIfAbsent(dimension,k->new LiveArchiveGate());}
    public CompletableFuture<GeographicWorker.WriteResult> write(String dimension,int x,int z,Entry[] columns){
        String key=Objects.requireNonNull(dimension);directory(key);
        var snapshot=new DiskGeographicPyramid.ChunkSnapshot(x,z,columns);
        return submit(()->{
            var world=open(key).world;var id=RegionShard.Id.ofChunk(x,z);
            world.ensureShard(id);
            world.updateChunks(id,List.of(snapshot));
            gate(key).committed(x,z);
            return GeographicWorker.WriteResult.COMMITTED;
        });
    }
    public PresentationChanges presentationChanges(String dimension){
        directory(dimension);return presentations.computeIfAbsent(dimension,k->new PresentationChanges());
    }
    public CompletableFuture<MapRaster> raster(String dimension,Key key){
        directory(dimension);Objects.requireNonNull(key);
        return submit(()->MapRaster.build(open(dimension).world.read(key)));
    }
    public CompletableFuture<WorldGeography.RenderPage> read(String dimension,Key key){
        directory(dimension);Objects.requireNonNull(key);
        return submit(()->open(dimension).world.read(key));
    }
    public CompletableFuture<Integer> handoff(String dimension,DirtyHandoffQueue queue){
        directory(dimension);Objects.requireNonNull(queue);
        return submit(()->queue.drain(open(dimension).dirty,256));
    }
    public CompletableFuture<ArchiveProgress> archiveStep(String dimension,Path regions,int minY,int height,
            OfflineRegionBackfill.Mapper mapper,int max){
        directory(dimension);Objects.requireNonNull(regions);Objects.requireNonNull(mapper);
        return submit(()->{
            var d=open(dimension);
            if(d.archive==null)d.archive=new ArchiveReconciler(regions,root.resolve(directory(dimension)).resolve("source"),minY,height,mapper);
            var p=d.archive.step(d.world,d.dirty,gate(dimension),max);
            return new ArchiveProgress(p.decoded(),p.unchanged(),p.committed(),p.deferred(),p.sourceErrors(),p.discovered(),p.pending());
        });
    }
    public CompletableFuture<Integer> pendingDirty(String dimension){
        directory(dimension);return submit(()->open(dimension).dirty.size());
    }
    public int queued(){return io.getQueue().size();}
    public boolean failed(){return failure!=null;}
    /** Call only at world stop, not normal ticks. Existing queued IO is drained. */
    public boolean close(long timeout,TimeUnit unit)throws InterruptedException{
        long end=System.nanoTime()+unit.toNanos(timeout);io.shutdown();
        if(!io.awaitTermination(timeout,unit))return false;
        Thread t=ownerThread;
        if(t!=null&&t.isAlive()){
            long left=end-System.nanoTime();if(left<=0)return false;
            TimeUnit.NANOSECONDS.timedJoin(t,left);
        }
        return (t==null||!t.isAlive())&&failure==null;
    }
}
