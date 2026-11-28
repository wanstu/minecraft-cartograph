package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/**
 * World-level style-independent page residency. All methods are owned by the
 * geographic IO thread, not a Minecraft tick or render thread. A single owner
 * coordinates fresh shard publication and incremental changes; external writes
 * are unsupported. Missing shards do not create files or generate world chunks.
 */
public final class WorldGeography {
    public record RenderPage(long generation,Page data){}
    public record Metrics(int residentShards,int residentPages,long residentPageBytes,
                          long pageHits,long assemblies,long sourceReads,long recoveryScanBytes,
                          long invalidatedPages,int publishedShards,long overviewHits,long overviewWrites,long overviewFailures){}
    private record Cached(RenderPage page,long weight){}
    private final Path root;
    private final WorldOverviewStore overview;
    private final PresentationChanges changes;
    private final int shardLimit,pageLimit;
    private final long pageBudget,perShardBudget;
    private final Set<RegionShard.Id> published=new HashSet<>();
    private final LinkedHashMap<RegionShard.Id,RegionShard> shards=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<Key,Cached> pages=new LinkedHashMap<>(16,.75f,true);
    private static final java.util.concurrent.atomic.AtomicLong GENERATIONS=new java.util.concurrent.atomic.AtomicLong();
    private long bytes,hits,assemblies,sourceReads,scanBytes,invalidations,overviewHits,overviewWrites,overviewFailures;
    private IOException failure;
    private final Thread owner=Thread.currentThread();
    public WorldGeography(Path root,int shardLimit,int pageLimit,long pageBudget,long shardBudget)throws IOException{
        this(root,shardLimit,pageLimit,pageBudget,shardBudget,new PresentationChanges());
    }
    WorldGeography(Path root,int shardLimit,int pageLimit,long pageBudget,long shardBudget,PresentationChanges changes)throws IOException{
        this.changes=Objects.requireNonNull(changes);
        if(shardLimit<1||pageLimit<1||pageBudget<1||shardBudget<shardLimit)throw new IllegalArgumentException("Cache budget");
        this.root=root;this.overview=new WorldOverviewStore(root);this.shardLimit=shardLimit;this.pageLimit=pageLimit;
        this.pageBudget=pageBudget;perShardBudget=shardBudget/shardLimit;
        Files.createDirectories(root);
        // One catalog pass per world open, not one scan per render request.
        try(var dirs=Files.newDirectoryStream(root,"shard.*")){
            for(Path dir:dirs){
                String[] parts=dir.getFileName().toString().split("\\.");
                if(parts.length!=3||!Files.isDirectory(dir))continue;
                RegionShard.Id id;
                try{id=new RegionShard.Id(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));}
                catch(NumberFormatException ex){continue;}
                if(!id.directory(root).equals(dir))continue;
                if(!Files.isRegularFile(dir.resolve("pages.cv4log")))throw new IOException("Missing shard log: "+dir);
                published.add(id);
            }
        }
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Geography IO owner");}
    private void available()throws IOException{owner();if(failure!=null)throw new IOException("Reopen geographic store after failed write",failure);}
    public Metrics metrics(){owner();return new Metrics(shards.size(),pages.size(),bytes,hits,assemblies,sourceReads,scanBytes,invalidations,published.size(),overviewHits,overviewWrites,overviewFailures);}
    private RegionShard open(RegionShard.Id id)throws IOException{
        RegionShard s=shards.get(id);if(s!=null)return s;
        if(!Files.isRegularFile(id.directory(root).resolve("pages.cv4log")))throw new IOException("Missing published shard");
        while(shards.size()>=shardLimit){var it=shards.keySet().iterator();it.next();it.remove();}
        s=new RegionShard(root,id,8,perShardBudget);scanBytes+=s.scanBytes();shards.put(id,s);return s;
    }
    /** Read one world page, including cross-shard coarse pages, without reading descendants. */
    public RenderPage read(Key key)throws IOException{
        available();Objects.requireNonNull(key);
        Cached cached=pages.get(key);
        if(cached!=null){hits++;return cached.page;}
        int cellsPerShard=RegionShard.BLOCKS>>key.lod();
        long bx=(long)key.x()*EDGE,bz=(long)key.z()*EDGE;
        long lastX=bx+EDGE-1,lastZ=bz+EDGE-1;
        if(bx<Integer.MIN_VALUE||lastX>Integer.MAX_VALUE||bz<Integer.MIN_VALUE||lastZ>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Page coordinates");
        if(WorldOverviewStore.supports(key)){
            Page stored=null;
            try{stored=overview.read(key);}
            catch(IOException e){overviewFailures++;}
            if(stored!=null){
                overviewHits++;
                long token=GENERATIONS.updateAndGet(Math::incrementExact);
                return remember(new RenderPage(token,Page.fromSlots(key,token,stored.copySlots())));
            }
        }
        int minX=(int)Math.floorDiv(bx,cellsPerShard),maxX=(int)Math.floorDiv(lastX,cellsPerShard);
        int minZ=(int)Math.floorDiv(bz,cellsPerShard),maxZ=(int)Math.floorDiv(lastZ,cellsPerShard);
        Entry[] slots=new Entry[EDGE*EDGE];
        for(long z=minZ;z<=maxZ;z++)for(long x=minX;x<=maxX;x++){
            var id=new RegionShard.Id((int)x,(int)z);
            if(!published.contains(id))continue;
            var s=open(id);long before=s.diskReads();
            Page source=s.readPage(key);sourceReads+=s.diskReads()-before;
            int x0=(int)Math.max(0,x*cellsPerShard-bx),x1=(int)Math.min(EDGE,(x+1)*cellsPerShard-bx);
            int z0=(int)Math.max(0,z*cellsPerShard-bz),z1=(int)Math.min(EDGE,(z+1)*cellsPerShard-bz);
            for(int iz=z0;iz<z1;iz++)for(int ix=x0;ix<x1;ix++){
                int slot=iz*EDGE+ix;slots[slot]=source.entryAt(slot);
            }
        }
        // Generation is a process-local upload token. Changes and evictions get new tokens.
        long token=GENERATIONS.updateAndGet(Math::incrementExact);assemblies++;
        Page p=Page.fromSlots(key,token,slots);
        if(WorldOverviewStore.supports(key)&&p.size()>0){
            try{overview.write(p);overviewWrites++;}
            catch(IOException e){overviewFailures++;}
        }
        return remember(new RenderPage(token,p));
    }
    private RenderPage remember(RenderPage result){
        Page p=result.data();Key key=p.key();
        long weight=RegionPageStore.estimatedWeight(p);
        if(weight<=pageBudget){
            while(!pages.isEmpty()&&(pages.size()>=pageLimit||bytes>pageBudget-weight)){
                var it=pages.values().iterator();bytes-=it.next().weight;it.remove();
            }
            pages.put(key,new Cached(result,weight));bytes+=weight;
        }
        return result;
    }
    private static Key chunkPage(int x,int z){return new Key(0,Math.floorDiv(x,2),Math.floorDiv(z,2));}
    public long chunkPageRevision(int x,int z)throws IOException{
        available();var id=RegionShard.Id.ofChunk(x,z);
        return published.contains(id)?open(id).revision(chunkPage(x,z)):0;
    }
    public boolean matchesChunk(int x,int z,Entry[] columns)throws IOException{
        available();if(columns.length!=256)throw new IllegalArgumentException("Chunk columns");
        var id=RegionShard.Id.ofChunk(x,z);
        if(!published.contains(id))return false;
        Page p=open(id).readPage(chunkPage(x,z));
        int ox=Math.floorMod(x,2)*16,oz=Math.floorMod(z,2)*16;
        for(int i=0;i<256;i++)if(!Objects.equals(columns[i],p.entryAt((oz+i/16)*32+ox+i%16)))return false;
        return true;
    }
    /** Create a new empty shard without forcing any Minecraft chunk generation. */
    public void ensureShard(RegionShard.Id id)throws IOException{
        available();
        if(!published.contains(id))buildFresh(id,(x,z)->null);
    }
    /** Fresh-only backfill owned by this IO worker. No map viewport dependency. */
    public FreshRegionBuilder.Result buildFresh(RegionShard.Id id,FreshRegionBuilder.PackedSource source)throws IOException{
        available();
        if(published.contains(id))throw new IOException("Shard already published");
        // Even a previously missing shard can change a persisted overview. Invalidate before publication.
        for(int lod=WorldOverviewStore.MIN_LOD;lod<=10;lod++){
            int divisor=1<<(lod-5);
            overview.invalidate(new Key(lod,Math.floorDiv(id.x(),divisor),Math.floorDiv(id.z(),divisor)));
        }
        var result=FreshRegionBuilder.buildPacked(root,id,source);
        published.add(id);
        if(result.chunks()>0)changes.reset();
        // Invalidate only resident pages intersecting the new shard.
        var it=pages.entrySet().iterator();
        while(it.hasNext()){
            var item=it.next();Key k=item.getKey();int cells=RegionShard.BLOCKS>>k.lod();
            long sx=(long)id.x()*cells,sz=(long)id.z()*cells,bx=(long)k.x()*EDGE,bz=(long)k.z()*EDGE;
            if(sx<bx+EDGE&&sx+cells>bx&&sz<bz+EDGE&&sz+cells>bz){
                bytes-=item.getValue().weight;it.remove();invalidations++;
            }
        }
        return result;
    }
    /** An existing shard update. Same-snapshot writes are true no-ops. */
    public int updateChunks(RegionShard.Id id,List<DiskGeographicPyramid.ChunkSnapshot> chunks)throws IOException{
        available();
        if(!published.contains(id))throw new IOException("Fresh shard must be published first");
        var shard=open(id);
        int writes;
        try{writes=shard.updateChunks(chunks,keys->{for(Key key:keys)overview.invalidate(key);});}
        catch(IOException e){failure=e;clearPresentationCache();throw e;}
        if(writes==0)return 0;
        Set<Key> changed=new HashSet<>();
        for(var chunk:chunks)for(int lod=0;lod<=10;lod++){
            int cellX=Math.floorDiv(Math.multiplyExact(chunk.x(),16),1<<lod);
            int cellZ=Math.floorDiv(Math.multiplyExact(chunk.z(),16),1<<lod);
            Key key=Key.of(new GeographicPyramid.Cell(lod,cellX,cellZ));
            changed.add(key);
            Cached removed=pages.remove(key);
            if(removed!=null){bytes-=removed.weight;invalidations++;}
        }
        changes.changed(changed);
        return writes;
    }
    private int maintenanceCursor;
    /** At most one already resident shard, without opening cold shards or invalidating render pages. */
    public RegionPageStore.Compaction compactResident(long minReclaim,long maxLiveBytes)throws IOException{
        available();if(shards.isEmpty())return new RegionPageStore.Compaction(0,0,0,false);
        var candidates=new ArrayList<>(shards.values());
        return candidates.get(Math.floorMod(maintenanceCursor++,candidates.size())).compact(minReclaim,maxLiveBytes);
    }
    public void clearPresentationCache(){owner();pages.clear();bytes=0;}
}
