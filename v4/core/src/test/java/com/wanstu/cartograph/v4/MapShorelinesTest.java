package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class MapShorelinesTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final byte L=MapShorelines.LAND,W=MapShorelines.WATER,U=MapShorelines.UNKNOWN;
    private static boolean contact(byte a,byte b){return a!=U&&b!=U&&a!=b;}
    private static Set<String> segments(List<MapShorelines.Run> runs){
        Set<String> out=new HashSet<>();
        for(var r:runs){
            check(r.x0()>=0&&r.x1()<=32&&r.z0()>=0&&r.z1()<=32,"bounds");
            if(r.x0()==r.x1()){
                check(r.z1()>r.z0(),"length");
                for(int z=r.z0();z<r.z1();z++)check(out.add("v:"+r.x0()+":"+z),"duplicate vertical edge");
            }else{
                check(r.z0()==r.z1()&&r.x1()>r.x0(),"horizontal");
                for(int x=r.x0();x<r.x1();x++)check(out.add("h:"+x+":"+r.z0()),"duplicate horizontal edge");
            }
        }
        return out;
    }
    public static void main(String[] args){
        var key=new Key(3,-2,-4);
        byte[] land=new byte[1024],water=new byte[1024],unknown=new byte[1024];
        Arrays.fill(land,L);Arrays.fill(water,W);
        var a=MapShorelines.build(key,land);
        var east=MapShorelines.build(new Key(3,-1,-4),water);
        var south=MapShorelines.build(new Key(3,-2,-3),water);
        check(a.interiorCount()==0&&east.interiorCount()==0,"homogeneous no internal edge");
        check(a.border(east,true).equals(List.of(new MapShorelines.Run(32,0,32,32))),"east seam merged");
        check(a.border(south,false).equals(List.of(new MapShorelines.Run(0,32,32,32))),"south seam merged");
        check(a.border(null,true).isEmpty(),"missing neighbor never creates coastline");
        var missing=MapShorelines.build(east.key(),unknown);
        check(a.border(missing,true).isEmpty(),"unknown neighbor never creates coastline");
        var plan=MapShorelines.plan(List.of(a,east,south),4096);
        check(plan.size()==1&&plan.getFirst().runs().size()==2,"unique seam ownership");
        var random=new Random(541);
        for(int iteration=0;iteration<200;iteration++){
            byte[] c=new byte[1024];
            for(int i=0;i<c.length;i++)c[i]=(byte)random.nextInt(3);
            Set<String> oracle=new HashSet<>();
            for(int z=0;z<32;z++)for(int x=0;x<32;x++){
                if(x>0&&contact(c[z*32+x-1],c[z*32+x]))oracle.add("v:"+x+":"+z);
                if(z>0&&contact(c[(z-1)*32+x],c[z*32+x]))oracle.add("h:"+x+":"+z);
            }
            var p=MapShorelines.build(key,c);
            check(segments(p.interior()).equals(oracle),"randomized edge oracle");
            byte[] neighbor=new byte[1024];for(int i=0;i<neighbor.length;i++)neighbor[i]=(byte)random.nextInt(3);
            var right=MapShorelines.build(east.key(),neighbor);var below=MapShorelines.build(south.key(),neighbor);
            Set<String> v=new HashSet<>(),h=new HashSet<>();
            for(int i=0;i<32;i++){
                if(contact(c[i*32+31],neighbor[i*32]))v.add("v:32:"+i);
                if(contact(c[31*32+i],neighbor[i]))h.add("h:"+i+":32");
            }
            check(segments(p.border(right,true)).equals(v)&&segments(p.border(below,false)).equals(h),"randomized perimeter oracle");
            var capped=MapShorelines.plan(List.of(p),17);
            check(capped.getFirst().runs().size()==17&&capped.getFirst().limited(),"bounded plan");
        }
        var waterFeature=new FeatureDictionary.Feature("minecraft:water","minecraft:river",FeatureDictionary.Terrain.WATER);
        var grassFeature=new FeatureDictionary.Feature("minecraft:grass_block","minecraft:plains",FeatureDictionary.Terrain.GRASS);
        var w=Entry.leaf(waterFeature,63,60,3,0,0,65535);
        var g=Entry.leaf(grassFeature,63,63,0,0,65535,0);
        var mixed=Aggregate.merge(List.of(w.stats(),w.stats(),g.stats(),g.stats()));
        var allWater=Aggregate.merge(List.of(w.stats(),w.stats(),w.stats(),w.stats()));
        var allGrass=Aggregate.merge(List.of(g.stats(),g.stats(),g.stats(),g.stats()));
        check(MapShorelines.classify(mixed,4,mixed.histogram())==U,"mixed cells do not invent exact edge");
        check(MapShorelines.classify(w.stats(),4,w.stats().histogram())==U,"partial cells do not invent exact edge");
        check(MapShorelines.classify(allWater,4,allWater.histogram())==W,"complete coarse water");
        check(MapShorelines.classify(allGrass,4,allGrass.histogram())==L,"complete coarse land");
        var coarse=MapRaster.build(new WorldGeography.RenderPage(1,new Page(new Key(1,0,0),1,Map.of(
            0,new Entry(allGrass,null),1,new Entry(allWater,null),2,new Entry(mixed,null),3,new Entry(g.stats(),null)))));
        check(coarse.shorelines().interior().equals(List.of(new MapShorelines.Run(1,0,1,1))),"raster integration");
        var flat=MapRaster.build(new WorldGeography.RenderPage(2,new Page(new Key(0,-1,0),2,Map.of(0,g,1,w))));
        check(flat.shorelines().interiorCount()==1,"LOD0 integration");
        System.out.println("PASS: 200 randomized shoreline oracles, exact cross-page ownership, unknown/mixed exclusion, negative coordinates, LOD and capped output");
    }
}
