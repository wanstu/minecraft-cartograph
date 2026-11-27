package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Initial construction from a frozen, read-only save or test copy. Never overwrites live geography. */
public final class OfflineRegionBackfill {
    private OfflineRegionBackfill(){}
    @FunctionalInterface public interface Mapper {
        FreshRegionBuilder.Columns map(SavedChunkSurface.Snapshot snapshot)throws IOException;
    }
    /** Build one 64x64-chunk shard from its four possible Anvil region files.
     * Source must be frozen (server stopped or independent copy). No live-use arbitration implied.
     */
    public static FreshRegionBuilder.Result buildShard(Path regionDirectory,Path target,RegionShard.Id id,
                                                       int minY,int height,Mapper mapper)throws IOException{
        Objects.requireNonNull(mapper);
        if(Files.exists(id.directory(target)))throw new IOException("Target shard already exists");
        int baseX=Math.multiplyExact(id.x(),2),baseZ=Math.multiplyExact(id.z(),2);
        Map<Long,AnvilRegionReader> readers=new HashMap<>();
        Map<Long,AnvilRegionReader.SavedChunk> candidates=new HashMap<>();
        try{
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                int rx=baseX+dx,rz=baseZ+dz;
                Path path=regionDirectory.resolve("r."+rx+"."+rz+".mca");
                if(Files.notExists(path))continue;
                var reader=new AnvilRegionReader(path,rx,rz);
                readers.put(key(rx,rz),reader);
                for(var c:reader.discover())candidates.put(key(c.x(),c.z()),c);
            }
            return FreshRegionBuilder.buildPacked(target,id,(x,z)->{
                var c=candidates.get(key(x,z));if(c==null)return null;
                var reader=readers.get(key(Math.floorDiv(x,32),Math.floorDiv(z,32)));
                SavedChunkSurface.Snapshot snapshot;
                try{snapshot=SavedChunkSurface.decode(reader.load(c).nbt(),x,z,minY,height);}
                catch(SavedChunkSurface.NotFullyGeneratedException notReady){return null;}
                var mapped=mapper.map(snapshot);
                if(mapped==null)throw new IOException("Mapper rejected observed chunk "+x+","+z);
                return mapped;
            });
        }finally{
            IOException failure=null;
            for(var reader:readers.values())try{reader.close();}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure!=null)throw failure;
        }
    }
    private static long key(int x,int z){return ((long)x<<32)|Integer.toUnsignedLong(z);}
}
