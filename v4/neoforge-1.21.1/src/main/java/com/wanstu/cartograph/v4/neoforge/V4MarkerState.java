package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import java.util.concurrent.*;

/** Client-owned snapshot; edits become visible only after durable IO acknowledgement. */
final class V4MarkerState {
    private final WorldIoService io;
    private final String dimension;
    private CompletableFuture<MarkerStore.Snapshot> pending;
    MarkerStore.Snapshot snapshot;
    String error;
    long completed;
    boolean success;
    V4MarkerState(WorldIoService io,String dimension){this.io=io;this.dimension=dimension;reload();}
    boolean busy(){return pending!=null;}
    void reload(){if(busy())return;error=null;pending=io.markers(dimension);}
    boolean change(MarkerStore.Change change){
        if(busy()||snapshot==null)return false;
        error=null;pending=io.changeMarker(dimension,snapshot.revision(),change);return true;
    }
    void tick(){
        if(pending==null||!pending.isDone())return;
        try{snapshot=pending.join();success=true;error=null;}
        catch(CompletionException|CancellationException e){
            success=false;Throwable cause=e.getCause()==null?e:e.getCause();
            error=cause.getMessage()==null?"Marker IO failed":cause.getMessage();
        }finally{pending=null;completed++;}
    }
}
