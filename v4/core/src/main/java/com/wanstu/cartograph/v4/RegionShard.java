package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/**
 * Experimental independent spatial partition. The 1024-block alignment is exact
 * for all supported L0-L10 cells. One owner per shard; no cross-shard transactions.
 * Higher-level render page assembly and world scheduling are not part of this class.
 */
public final class RegionShard {
    public static final int BLOCKS=1024, CHUNKS=BLOCKS/16, MAX_LOD=10;
    public record Id(int x,int z){
        public static Id ofChunk(int x,int z){return new Id(Math.floorDiv(x,CHUNKS),Math.floorDiv(z,CHUNKS));}
        public static Id ofCell(GeographicPyramid.Cell c){
            if(c.level()<0||c.level()>MAX_LOD)throw new IllegalArgumentException("LOD");
            int cells=BLOCKS>>c.level();
            return new Id(Math.floorDiv(c.x(),cells),Math.floorDiv(c.z(),cells));
        }
        public Path directory(Path root){return root.resolve("shard."+x+"."+z);}
    }
    private final Id id;
    private final RegionPageStore store;
    private final DiskGeographicPyramid pyramid;
    public RegionShard(Path root,Id id,int pageLimit,long cacheBytes)throws IOException{
        this.id=id;
        store=new RegionPageStore(id.directory(root),pageLimit,cacheBytes);
        pyramid=new DiskGeographicPyramid(store,MAX_LOD);
    }
    public int updateChunks(List<DiskGeographicPyramid.ChunkSnapshot> chunks)throws IOException{
        for(var chunk:chunks)if(!id.equals(Id.ofChunk(chunk.x(),chunk.z())))
            throw new IllegalArgumentException("Chunk outside shard");
        return pyramid.updateChunks(chunks);
    }
    public Entry get(GeographicPyramid.Cell cell)throws IOException{
        if(!id.equals(Id.ofCell(cell)))throw new IllegalArgumentException("Cell outside shard");
        return pyramid.get(cell);
    }
    long revision(Key key){return store.revision(key);}
    Page readPage(Key key)throws IOException{return store.read(key);}
    public long diskWrites(){return store.diskWrites();}
    public long diskReads(){return store.diskReads();}
    public long flushes(){return store.durableFlushes();}
    public long cacheBytes(){return store.estimatedCacheBytes();}
    public long scanBytes(){return store.startupScanBytes();}
}
