package com.wanstu.cartograph.v4;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.StructureIndex.*;
public final class StructureViewTest{
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        var jobs=new ArrayDeque<CompletableFuture<Result>>();
        var view=new StructureView(b->{var f=new CompletableFuture<Result>();jobs.add(f);return f;});
        var a=StructureView.bounds(new MapProjection.Bounds(-500,0,-10,400));
        var b=StructureView.bounds(new MapProjection.Bounds(1,0,400,400));
        check(a.equals(new Bounds(-32,0,-1,31)),"negative region alignment");
        view.tick(a,0);view.tick(b,0);check(jobs.size()==1,"one request");
        jobs.remove().complete(new Result(List.of(new Start("minecraft:village_plains",-1,0)),false));
        check(view.tick(b,0).starts().isEmpty()&&jobs.size()==1,"stale pan discarded");
        var start=new Start("test:port",1,0);
        jobs.remove().complete(new Result(List.of(start),false));
        for(int i=0;i<1000;i++)check(view.tick(b,0).starts().equals(List.of(start)),"warm snapshot");
        check(jobs.isEmpty(),"no repeated reads");
        check(view.tick(b,1).starts().isEmpty()&&jobs.size()==1,"revision invalidation");
        jobs.remove().complete(new Result(List.of(),false));view.tick(b,1);
        check(view.tick(new Bounds(-10000,-10000,10000,10000),1).limited()&&jobs.isEmpty(),"wide view limited");
        System.out.println("PASS: aligned negative bounds, one request, obsolete result rejection, 1000 warm frames, revision invalidation and wide-view limit");
    }
}
