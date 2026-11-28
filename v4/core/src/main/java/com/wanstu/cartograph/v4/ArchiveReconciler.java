package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.ActiveChunkScheduler.Position;

/** Bounded saved-world reconciliation, run exclusively by the world IO owner. */
final class ArchiveReconciler implements AutoCloseable {
    record Progress(long decoded,long unchanged,long committed,long deferred,long sourceErrors,boolean discovered,int pending,int waitingActive,String lastSourceError){}
    private record Candidate(Path file,int rx,int rz,int x,int z,SavedDirtyIndex.Work work){}
    private record Ready(Candidate candidate,byte[] hash,Entry[] columns,boolean alreadyMatches,List<StructureIndex.Start> starts){}
    private final Path regions;
    private final int minY,height;
    private final OfflineRegionBackfill.Mapper mapper;
    private final SourceFingerprints fingerprints;
    private final StructureIndex structures;
    private final Runnable structureChanged;
    private SavedArchiveCursor cursor;
    private boolean discovered;
    private static final int MAX_FAILED_REGIONS=256;
    private final Set<Path> failedRegions=new LinkedHashSet<>();
    private boolean rescan=true;
    private int sinceFlush;
    private long decoded,unchanged,committed,deferred,errors;
    private String lastSourceError="";
    static String describe(String where,Throwable error){
        String message=(where+": "+error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"")).replace('\n',' ').replace('\r',' ');
        return message.substring(0,Math.min(240,message.length()));
    }
    private void sourceError(String where,Throwable error){errors++;lastSourceError=describe(where,error);}
    ArchiveReconciler(Path regions,Path index,int minY,int height,OfflineRegionBackfill.Mapper mapper)throws IOException{
        this(regions,index,minY,height,mapper,null,()->{});
    }
    ArchiveReconciler(Path regions,Path index,int minY,int height,OfflineRegionBackfill.Mapper mapper,StructureIndex structures,Runnable structureChanged)throws IOException{
        this.regions=regions;this.minY=minY;this.height=height;this.mapper=Objects.requireNonNull(mapper);this.structures=structures;this.structureChanged=structureChanged;
        fingerprints=new SourceFingerprints(index);
    }
    Progress progress(SavedDirtyIndex dirty){return new Progress(decoded,unchanged,committed,deferred,errors,discovered,dirty.size(),dirty.waitingActive(),lastSourceError);}
    private Candidate dirty(SavedDirtyIndex.Work w){
        int x=w.position().x(),z=w.position().z(),rx=Math.floorDiv(x,32),rz=Math.floorDiv(z,32);
        return new Candidate(regions.resolve("r."+rx+"."+rz+".mca"),rx,rz,x,z,w);
    }
    Progress step(WorldGeography world,SavedDirtyIndex dirty,LiveArchiveGate gate,int max)throws IOException{
        if(max<1||max>32)throw new IllegalArgumentException("Archive budget");
        for(var p:gate.drainWakeups(max))dirty.wake(p);
        List<Candidate> candidates=new ArrayList<>();
        // Reserve capacity for initial discovery even with a permanently deferred dirty item.
        for(var w:dirty.peek(discovered?max:Math.max(1,max/4)))candidates.add(dirty(w));
        if(!discovered&&candidates.size()<max){
            try{
                if(cursor==null){
                    if(rescan){
                        // Only known absence counts as empty; inaccessible sources remain retryable.
                        if(Files.notExists(regions))discovered=true;
                        else cursor=new SavedArchiveCursor(regions);
                    }else cursor=new SavedArchiveCursor(failedRegions);
                    failedRegions.clear();rescan=false;
                }
                if(cursor!=null){
                    var step=cursor.step(2,max-candidates.size());
                    errors+=step.failedRegions().size();
                    if(!step.failedRegions().isEmpty())lastSourceError=cursor.lastFailure();
                    for(Path path:step.failedRegions()){
                        if(failedRegions.size()<MAX_FAILED_REGIONS)failedRegions.add(path);
                        else rescan=true; // bounded memory; overflow requires a fresh pass
                    }
                    for(var c:step.candidates())candidates.add(new Candidate(c.path(),c.regionX(),c.regionZ(),c.chunk().x(),c.chunk().z(),null));
                    if(step.exhausted()){
                        cursor.close();cursor=null;
                        discovered=!rescan&&failedRegions.isEmpty();
                    }
                }
            }catch(IOException e){
                // Source discovery failures must not poison the geography IO owner.
                sourceError("region discovery",e);rescan=true;
                if(cursor!=null){try{cursor.close();}catch(IOException ignored){}cursor=null;}
                // Still process the durable dirty candidates already selected.
            }
        }
        Map<RegionShard.Id,List<Ready>> batches=new LinkedHashMap<>();
        Set<Position> seen=new HashSet<>();
        List<SavedDirtyIndex.Work> ack=new ArrayList<>();
        List<SavedDirtyIndex.Work> retry=new ArrayList<>();
        Set<Position> newRetry=new LinkedHashSet<>();
        try{
            for(Candidate c:candidates){
                if(!seen.add(new Position(c.x,c.z)))continue;
                if(c.work!=null&&gate.parkIfActive(c.work.position())){dirty.park(c.work);deferred++;continue;}
                if(!gate.begin(c.x,c.z)){
                    deferred++;
                    if(c.work!=null){if(gate.parkIfActive(c.work.position()))dirty.park(c.work);else retry.add(c.work);}
                    continue;
                }
                boolean retained=false;
                try{
                    AnvilRegionReader.Encoded encoded;
                    try(var reader=new AnvilRegionReader(c.file,c.rx,c.rz)){
                        var identity=reader.identity(c.x,c.z);
                        if(identity==null){deferred++;if(c.work!=null)retry.add(c.work);continue;}
                        encoded=reader.encoded(identity);
                    }catch(IOException e){sourceError("region read "+c.x+","+c.z,e);if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;}
                    byte[] hash=encoded.fingerprint();
                    long revision=world.chunkPageRevision(c.x,c.z);
                    // A terrain revision alone cannot prove the saved structure starts
                    // still agree after a structure-only live commit. Verify the exact
                    // saved facts whenever this session owns the live chunk.
                    if(!gate.protectedLive(c.x,c.z)&&fingerprints.matches(c.x,c.z,revision,hash)&&gate.valid(c.x,c.z)){
                        unchanged++;if(c.work!=null)ack.add(c.work);continue;
                    }
                    Entry[] columns;List<StructureIndex.Start> starts;
                    try{
                        var source=SavedChunkSurface.decode(encoded.decode().nbt(),c.x,c.z,minY,height);
                        var mapped=mapper.map(source);
                        if(mapped==null)throw new IOException("Unmapped saved chunk");
                        columns=mapped.toEntries();starts=source.starts();decoded++;
                    }catch(SavedChunkSurface.NotFullyGeneratedException e){
                        deferred++;if(c.work!=null)retry.add(c.work);continue;
                    }catch(IOException|RuntimeException e){
                        sourceError("chunk decode "+c.x+","+c.z,e);if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;
                    }
                    if(!gate.valid(c.x,c.z)){deferred++;if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;}
                    boolean equal=world.matchesChunk(c.x,c.z,columns);
                    // A recently committed live capture is authoritative during this
                    // server session. Never replace it with a conflicting disk snapshot.
                    boolean sameStarts=true;
                    if(structures!=null&&gate.protectedLive(c.x,c.z))try{sameStarts=structures.matches(c.x,c.z,starts);}
                    catch(IOException ex){sourceError("structure match "+c.x+","+c.z,ex);if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;}
                    if(gate.protectedLive(c.x,c.z)&&(!equal||!sameStarts)){
                        deferred++;if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;
                    }
                    batches.computeIfAbsent(RegionShard.Id.ofChunk(c.x,c.z),k->new ArrayList<>())
                        .add(new Ready(c,hash,columns,equal,starts));
                    retained=true;
                }finally{if(!retained)gate.end(c.x,c.z);}
            }
            for(var batch:batches.entrySet()){
                List<Ready> accepted=new ArrayList<>();
                List<DiskGeographicPyramid.ChunkSnapshot> writes=new ArrayList<>();
                for(Ready r:batch.getValue()){
                    Candidate c=r.candidate;
                    if(!gate.valid(c.x,c.z)){deferred++;if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;}
                    accepted.add(r);
                    if(!r.alreadyMatches)writes.add(new DiskGeographicPyramid.ChunkSnapshot(c.x,c.z,r.columns));
                }
                if(!writes.isEmpty()){
                    world.ensureShard(batch.getKey());world.updateChunks(batch.getKey(),writes);
                }
                for(Ready r:accepted){
                    Candidate c=r.candidate;
                    if(r.alreadyMatches)unchanged++;else committed++;
                    if(!gate.valid(c.x,c.z)){if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;}
                    if(structures!=null)try{if(structures.replace(c.x,c.z,r.starts))structureChanged.run();}
                    catch(IOException e){sourceError("structure write "+c.x+","+c.z,e);if(c.work!=null)retry.add(c.work);else newRetry.add(new Position(c.x,c.z));continue;}
                    fingerprints.record(c.x,c.z,world.chunkPageRevision(c.x,c.z),r.hash);sinceFlush++;
                    if(c.work!=null)ack.add(c.work);
                }
            }
            // Only a durable geographic commit, or an exact matching source, may clear work.
            if(!newRetry.isEmpty())dirty.mark(List.copyOf(newRetry));
            dirty.acknowledgeBatch(ack);
            for(var w:retry)dirty.rotate(w);
            if(sinceFlush>=128||discovered){fingerprints.flush();sinceFlush=0;}
            return progress(dirty);
        }finally{
            for(var batch:batches.values())for(Ready r:batch)gate.end(r.candidate.x,r.candidate.z);
        }
    }
    @Override public void close()throws IOException{
        try{if(cursor!=null)cursor.close();}finally{fingerprints.flush();}
    }
}
