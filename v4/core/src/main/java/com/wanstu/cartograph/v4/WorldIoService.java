package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** One bounded IO owner shared across dimensions. No synchronous disk work on caller threads. */
public final class WorldIoService {
    private record Dimension(WorldGeography world,SavedDirtyIndex dirty){}
    private final Path root;
    private final int dimensionLimit;
    private final Map<String,Dimension> dimensions=new LinkedHashMap<>(16,.75f,true);
    private final ThreadPoolExecutor io;
    private volatile Throwable failure;
    public WorldIoService(Path root,int dimensionLimit,int queueLimit){
        this.root=Objects.requireNonNull(root);
        if(dimensionLimit<1||queueLimit<1)throw new IllegalArgumentException("IO limits");
        this.dimensionLimit=dimensionLimit;
        io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queueLimit),r->{
            Thread t=new Thread(r,"cartograph-v4-world-io");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;
        });
    }
    private static String directory(String dimension){
        if(!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Dimension key");
        return java.util.HexFormat.of().formatHex(dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private Dimension open(String key)throws IOException{
        Dimension d=dimensions.get(key);if(d!=null)return d;
        if(dimensions.size()>=dimensionLimit){
            var it=dimensions.keySet().iterator();it.next();it.remove();
        }
        Path path=root.resolve(directory(key));
        d=new Dimension(new WorldGeography(path,4,32,8L*1024*1024,8L*1024*1024),new SavedDirtyIndex(path));
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
    public CompletableFuture<GeographicWorker.WriteResult> write(String dimension,int x,int z,Entry[] columns){
        String key=Objects.requireNonNull(dimension);directory(key);
        var snapshot=new DiskGeographicPyramid.ChunkSnapshot(x,z,columns);
        return submit(()->{
            var world=open(key).world;var id=RegionShard.Id.ofChunk(x,z);
            world.ensureShard(id);
            world.updateChunks(id,List.of(snapshot));
            return GeographicWorker.WriteResult.COMMITTED;
        });
    }
    public CompletableFuture<WorldGeography.RenderPage> read(String dimension,Key key){
        directory(dimension);Objects.requireNonNull(key);
        return submit(()->open(dimension).world.read(key));
    }
    public CompletableFuture<Integer> handoff(String dimension,DirtyHandoffQueue queue){
        directory(dimension);Objects.requireNonNull(queue);
        return submit(()->queue.drain(open(dimension).dirty,256));
    }
    public CompletableFuture<Integer> pendingDirty(String dimension){
        directory(dimension);return submit(()->open(dimension).dirty.size());
    }
    public int queued(){return io.getQueue().size();}
    public boolean failed(){return failure!=null;}
    /** Call only at world stop, not normal ticks. Existing queued IO is drained. */
    public boolean close(long timeout,TimeUnit unit)throws InterruptedException{
        io.shutdown();return io.awaitTermination(timeout,unit);
    }
}
