package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.BiFunction;
import static com.wanstu.cartograph.v4.StructureIndex.*;

/** Render-owned, region-aligned asynchronous viewport query. One pending job, no stale display. */
public final class StructureView {
    private final BiFunction<Bounds,Integer,CompletableFuture<Result>> source;
    private Bounds requested,accepted;
    private int requestedMask,acceptedMask;
    private CompletableFuture<Result> pending;
    private Result result=new Result(List.of(),false);
    private long pendingRevision,acceptedRevision=-1,retryAt,frame;
    private String error;
    public StructureView(Function<Bounds,CompletableFuture<Result>> source){this((b,mask)->source.apply(b));}
    public StructureView(BiFunction<Bounds,Integer,CompletableFuture<Result>> source){this.source=Objects.requireNonNull(source);}
    public static Bounds bounds(MapProjection.Bounds b){
        int x0=chunk(b.minX()),z0=chunk(b.minZ()),x1=chunk(b.maxX()),z1=chunk(b.maxZ());
        return new Bounds(Math.max(-1875000,Math.floorDiv(x0,32)*32),
            Math.max(-1875000,Math.floorDiv(z0,32)*32),
            Math.min(1875000,Math.floorDiv(x1,32)*32+31),
            Math.min(1875000,Math.floorDiv(z1,32)*32+31));
    }
    private static int chunk(double x){return (int)Math.floor(Math.max(-30_000_000,Math.min(30_000_000,x))/16);}
    public Result tick(Bounds wanted,long revision){return tick(wanted,revision,StructureCatalog.ALL_CATEGORIES);}
    public Result tick(Bounds wanted,long revision,int mask){
        frame++;
        if(pending!=null&&pending.isDone()){
            try{
                var value=pending.join();
                if(requested.equals(wanted)&&pendingRevision==revision&&requestedMask==mask){
                    result=value;accepted=requested;acceptedRevision=revision;acceptedMask=mask;error=null;
                }
            }catch(CompletionException|CancellationException ex){error="Structure read failed";retryAt=frame+60;}
            pending=null;
        }
        if(mask==0)return new Result(List.of(),false);
        if(!wanted.bounded())return new Result(List.of(),true);
        if(wanted.equals(accepted)&&acceptedRevision==revision&&acceptedMask==mask)return result;
        if(pending==null&&frame>=retryAt){
            requested=wanted;pendingRevision=revision;requestedMask=mask;
            pending=source.apply(wanted,mask);
        }
        return new Result(List.of(),false);
    }
    public boolean busy(){return pending!=null;}
    public String error(){return error;}
}
