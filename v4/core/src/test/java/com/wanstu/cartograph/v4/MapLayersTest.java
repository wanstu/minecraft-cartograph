package com.wanstu.cartograph.v4;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.MapLayers.Layer;
import static com.wanstu.cartograph.v4.StructureCatalog.*;
import static com.wanstu.cartograph.v4.StructureIndex.*;

public final class MapLayersTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        var layers=new MapLayers();
        check(!layers.visible(Layer.CONTOURS)&&layers.visible(Layer.MARKERS)&&layers.visible(Layer.REGIONS)&&layers.structuresActive(),"defaults");
        for(var layer:Layer.values()){
            boolean before=layers.visible(layer);layers.toggle(layer);
            check(layers.visible(layer)!=before,"toggle");layers.toggle(layer);
        }
        layers.setAllCategories(false);check(!layers.structuresActive()&&layers.visible(Layer.STRUCTURES),"empty selection");
        layers.toggleCategory(Category.WATER);check(layers.structuresActive()&&layers.categoryMask()==bit(Category.WATER),"category independent");
        layers.toggle(Layer.STRUCTURES);check(!layers.structuresActive()&&layers.categoryVisible(Category.WATER),"master preserves selection");
        layers.toggle(Layer.STRUCTURES);layers.setAllCategories(true);check(layers.categoryMask()==ALL_CATEGORIES,"all");
        var bounds=new Bounds(0,0,31,31);
        Path root=Files.createTempDirectory("cv4-filter");
        try{
            var index=new StructureIndex(root);
            var village=new Start("minecraft:village_plains",0,0);
            var ship=new Start("minecraft:shipwreck",1,0);
            var other=new Start("mod:village_plains",2,0);
            index.replace(0,0,List.of(village));index.replace(1,0,List.of(ship));index.replace(2,0,List.of(other));
            check(index.query(bounds,bit(Category.WATER)).starts().equals(List.of(ship)),"water selection");
            check(index.query(bounds,bit(Category.OTHER)).starts().equals(List.of(other)),"namespace safe");
            check(index.query(bounds,0).starts().isEmpty(),"empty selection");
            // A rare category must not be hidden behind the all-category result limit.
            for(int chunk=3;chunk<24;chunk++){
                var starts=new ArrayList<Start>();
                for(int k=0;k<100;k++)starts.add(new Start("test:other_"+k,chunk,0));
                index.replace(chunk,0,starts);
            }
            var rare=new Start("minecraft:ancient_city",25,0);
            index.replace(25,0,List.of(rare));
            check(index.query(bounds).limited(),"overall limit");
            check(index.query(bounds,bit(Category.UNDERGROUND)).starts().equals(List.of(rare)),"filter before cap");
        }finally{ScaleTest.delete(root);}
        var jobs=new ArrayDeque<CompletableFuture<Result>>();
        var masks=new ArrayList<Integer>();
        var view=new StructureView((b,mask)->{masks.add(mask);var f=new CompletableFuture<Result>();jobs.add(f);return f;});
        var ship=new Start("minecraft:shipwreck",1,0);
        view.tick(bounds,1,ALL_CATEGORIES);
        view.tick(bounds,1,bit(Category.WATER));check(jobs.size()==1,"bounded pending");
        jobs.remove().complete(new Result(List.of(new Start("minecraft:village_plains",0,0)),false));
        check(view.tick(bounds,1,bit(Category.WATER)).starts().isEmpty()&&jobs.size()==1,"stale mask discarded");
        jobs.remove().complete(new Result(List.of(ship),false));
        for(int i=0;i<1000;i++)check(view.tick(bounds,1,bit(Category.WATER)).starts().equals(List.of(ship)),"warm filtered result");
        check(jobs.isEmpty()&&masks.equals(List.of(ALL_CATEGORIES,bit(Category.WATER))),"no repeated query");
        check(view.tick(bounds,1,0).starts().isEmpty()&&jobs.isEmpty(),"zero filter no IO");
        System.out.println("PASS: independent layer state, category filtering before limits, stale mask and warm reuse");
    }
}
