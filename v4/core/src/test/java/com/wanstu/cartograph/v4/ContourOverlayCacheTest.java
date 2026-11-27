package com.wanstu.cartograph.v4;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
public final class ContourOverlayCacheTest{
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        var jobs=new ArrayDeque<Runnable>();
        var center=ContourLinesTest.raster(-1,0,false,false);
        var east=ContourLinesTest.raster(0,0,false,false);
        var a=new ContourOverlayCache.Input(center,null,null,null);
        var b=new ContourOverlayCache.Input(center,east,null,null);
        try(var cache=new ContourOverlayCache(jobs::add,false)){
            check(cache.tick(List.of(a)).pending()==1&&jobs.size()==1,"one job");
            cache.tick(List.of(b));check(jobs.size()==1,"bounded stale work");
            jobs.remove().run();
            check(cache.tick(List.of(b)).ready().isEmpty()&&jobs.size()==1,"stale halo rejected");
            jobs.remove().run();
            var first=cache.tick(List.of(b));
            check(first.ready().size()==1&&first.ready().getFirst().size()>0&&first.builds()==1,"ready result");
            for(int i=0;i<1000;i++)check(cache.tick(List.of(b)).ready().getFirst()==first.ready().getFirst(),"warm identity");
            cache.tick(List.of());check(cache.tick(List.of(b)).builds()==1&&jobs.isEmpty(),"pan reuse");
            cache.tick(List.of(a));jobs.remove().run();
            check(cache.tick(List.of(a)).builds()==2,"changed dependency rebuild");
        }
        System.out.println("PASS: one asynchronous contour job, obsolete halo rejection, 1000 warm reuses, pan reuse and local rebuild");
    }
}
