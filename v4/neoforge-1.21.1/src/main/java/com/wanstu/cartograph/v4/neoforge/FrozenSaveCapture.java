package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import java.io.IOException;
import java.nio.file.Path;

/** Explicit offline/copy-only entrypoint. Call after registries are initialized, off the tick thread. */
public final class FrozenSaveCapture {
    private FrozenSaveCapture(){}
    public static FreshRegionBuilder.Result buildShard(Path regions,Path newGeography,RegionShard.Id shard,
                                                       int minY,int height)throws IOException{
        return OfflineRegionBackfill.buildShard(regions,newGeography,shard,minY,height,MinecraftSurfaceFacts::fromSaved);
    }
}