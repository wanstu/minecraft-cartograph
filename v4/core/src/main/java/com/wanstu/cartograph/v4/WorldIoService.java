package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** One bounded IO owner shared across dimensions. No synchronous disk work on caller threads. */
public final class WorldIoService {
    private static final class Dimension{
        final WorldGeography world;final SavedDirtyIndex dirty;final StructureIndex structures;ArchiveReconciler archive;
        Dimension(WorldGeography world,SavedDirtyIndex dirty,StructureIndex structures){this.world=world;this.dirty=dirty;this.structures=structures;}
        void close()throws IOException{if(archive!=null)archive.close();}
    }
    private final Path root;
    private final int dimensionLimit;
    private final Map<String,Dimension> dimensions=new LinkedHashMap<>(16,.75f,true);
    private final ThreadPoolExecutor io;
    private final Map<String,LiveArchiveGate> gates=new ConcurrentHashMap<>();
    private final Map<String,PresentationChanges> presentations=new ConcurrentHashMap<>();
    private final Map<String,java.util.concurrent.atomic.AtomicLong> structureRevisions=new ConcurrentHashMap<>();
    public record ArchiveProgress(long decoded,long unchanged,long committed,long deferred,long sourceErrors,boolean discovered,int pending,int waitingActive,String lastSourceError){}
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
        d=new Dimension(new WorldGeography(path,4,32,8L*1024*1024,8L*1024*1024,presentationChanges(key)),new SavedDirtyIndex(path),new StructureIndex(path.resolve("structures-v2")));
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
        return write(dimension,x,z,columns,null);
    }
    public CompletableFuture<GeographicWorker.WriteResult> write(String dimension,int x,int z,Entry[] columns,List<StructureIndex.Start> starts){
        String key=Objects.requireNonNull(dimension);directory(key);
        List<StructureIndex.Start> facts=starts==null?null:List.copyOf(starts);
        var snapshot=new DiskGeographicPyramid.ChunkSnapshot(x,z,columns);
        return submit(()->{
            var world=open(key).world;var id=RegionShard.Id.ofChunk(x,z);
            world.ensureShard(id);
            world.updateChunks(id,List.of(snapshot));
            gate(key).committed(x,z);
            if(facts!=null)try{if(open(key).structures.replace(x,z,facts))structureCounter(key).incrementAndGet();}
            catch(IOException ex){throw new java.io.UncheckedIOException(ex);}
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
            if(d.archive==null)d.archive=new ArchiveReconciler(regions,root.resolve(directory(dimension)).resolve("source-structures-v2"),minY,height,mapper,d.structures,()->structureCounter(dimension).incrementAndGet());
            var p=d.archive.step(d.world,d.dirty,gate(dimension),max);
            return new ArchiveProgress(p.decoded(),p.unchanged(),p.committed(),p.deferred(),p.sourceErrors(),p.discovered(),p.pending(),p.waitingActive(),p.lastSourceError());
        });
    }
    public CompletableFuture<Integer> pendingDirty(String dimension){
        directory(dimension);return submit(()->open(dimension).dirty.size());
    }
    private java.util.concurrent.atomic.AtomicLong structureCounter(String dimension){
        directory(dimension);return structureRevisions.computeIfAbsent(dimension,k->new java.util.concurrent.atomic.AtomicLong());
    }
    public long structureRevision(String dimension){return structureCounter(dimension).get();}
    public CompletableFuture<StructureIndex.Result> structures(String dimension,StructureIndex.Bounds bounds){
        return structures(dimension,bounds,StructureCatalog.ALL_CATEGORIES);
    }
    public CompletableFuture<StructureIndex.Result> structures(String dimension,StructureIndex.Bounds bounds,int categories){
        directory(dimension);Objects.requireNonNull(bounds);
        return submit(()->{try{return open(dimension).structures.query(bounds,categories);}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}});
    }
    private MarkerStore markerStore(String dimension){return new MarkerStore(root.resolve(directory(dimension)).resolve("markers.cv4"));}
    public CompletableFuture<MarkerStore.Snapshot> markers(String dimension){
        directory(dimension);
        return submit(()->{try{return markerStore(dimension).read();}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}});
    }
    public CompletableFuture<MarkerStore.Snapshot> changeMarker(String dimension,long expected,MarkerStore.Change change){
        directory(dimension);Objects.requireNonNull(change);
        return submit(()->{try{return markerStore(dimension).apply(expected,change);}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}});
    }
    private NamedRegionStore regionStore(String dimension){return new NamedRegionStore(root.resolve(directory(dimension)).resolve("named-regions.cv4"));}
    public CompletableFuture<NamedRegionStore.Snapshot> namedRegions(String dimension){
        directory(dimension);return submit(()->{try{return regionStore(dimension).read();}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}});
    }
    public CompletableFuture<NamedRegionStore.Snapshot> changeRegion(String dimension,long expected,NamedRegionStore.Change change){
        directory(dimension);Objects.requireNonNull(change);
        return submit(()->{try{return regionStore(dimension).apply(expected,change);}catch(IOException ex){throw new java.io.UncheckedIOException(ex);}});
    }
    /** Best-effort low-priority maintenance. A busy queue never initiates compaction. */
    public CompletableFuture<RegionPageStore.Compaction> compactResident(String dimension){
        directory(dimension);
        return submit(()->{
            var d=dimensions.get(dimension);
            if(d==null||!io.getQueue().isEmpty())return new RegionPageStore.Compaction(0,0,0,false);
            // Maintenance failure (disk full, unsupported atomic move) is reported without
            // treating an otherwise readable original log as a failed geographic write.
            try{return d.world.compactResident(8L*1024*1024,8L*1024*1024);}
            catch(IOException e){throw new java.io.UncheckedIOException(e);}
        });
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
