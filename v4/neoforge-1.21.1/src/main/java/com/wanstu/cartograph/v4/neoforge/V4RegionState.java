package com.wanstu.cartograph.v4.neoforge;
import com.wanstu.cartograph.v4.*;
import java.util.concurrent.*;

final class V4RegionState {
    private final WorldIoService io;
    private final String dimension;
    private CompletableFuture<NamedRegionStore.Snapshot> pending;
    NamedRegionStore.Snapshot snapshot;
    long completed;
    boolean success,visible=true;
    String error;
    V4RegionState(WorldIoService io,String dimension){this.io=io;this.dimension=dimension;reload();}
    boolean busy(){return pending!=null;}
    void reload(){if(!busy()){error=null;pending=io.namedRegions(dimension);}}
    boolean change(NamedRegionStore.Change change){
        if(busy()||snapshot==null)return false;
        error=null;pending=io.changeRegion(dimension,snapshot.revision(),change);return true;
    }
    void tick(){
        if(pending==null||!pending.isDone())return;
        try{snapshot=pending.join();success=true;error=null;}
        catch(CompletionException|CancellationException e){success=false;error="Region operation failed: "+e.getMessage();}
        finally{pending=null;completed++;}
    }
}
