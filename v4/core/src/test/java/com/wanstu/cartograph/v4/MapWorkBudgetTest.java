package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

public final class MapWorkBudgetTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        var budget=new MapWorkBudget();
        check(budget.pressure()==0&&budget.textureUploads()==4&&budget.meshUploads()==2&&budget.prefetchNeighbors(),"normal");
        budget.sample(9_000_000);budget.sample(9_000_000);
        check(budget.pressure()==0,"no response to transient spike");
        budget.sample(4_000_000);check(budget.pressure()==1&&!budget.prefetchNeighbors()&&budget.textureUploads()==2,"sustained pressure");
        for(int i=0;i<3;i++)budget.sample(8_000_000);
        check(budget.pressure()==2&&budget.textureUploads()==1&&budget.meshUploads()==1&&budget.sliceNanos()==500_000,"minimum forward progress");
        for(int i=0;i<100;i++)budget.sample(8_000_000);check(budget.pressure()==2,"bounded");
        for(int i=0;i<59;i++)budget.sample(1_000_000);
        check(budget.pressure()==2,"slow recovery");
        budget.sample(1_000_000);check(budget.pressure()==1,"one level recovery");
        for(int i=0;i<60;i++)budget.sample(1_000_000);
        check(budget.pressure()==0,"normal restored");
        var visible=List.of(new Key(2,-1,-1),new Key(2,0,0));
        var urgent=MapTileCoverage.requests(visible,66,false);
        var full=MapTileCoverage.requests(visible,66,true);
        check(urgent.containsAll(visible)&&urgent.containsAll(visible.stream().map(MapTileCoverage::parent).toList()),"exact and parent never suppressed");
        check(urgent.size()==4&&full.size()>urgent.size()&&full.containsAll(urgent),"only optional ring suppressed");
        System.out.println("PASS: bounded pressure hysteresis, minimum upload progress, recovery and optional prefetch suppression");
    }
}
