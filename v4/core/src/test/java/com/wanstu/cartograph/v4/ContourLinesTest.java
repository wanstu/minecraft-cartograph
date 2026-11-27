package com.wanstu.cartograph.v4;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
public final class ContourLinesTest{
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static MapRaster raster(int px,int pz,boolean missing,boolean flat){
        var f=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        Map<Integer,Entry> entries=new HashMap<>();
        for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            if(missing&&x==31)continue;
            int y=flat?70:px*32+x+64;
            entries.put(z*32+x,Entry.leaf(f,y+10,y,0,100,100,100));
        }
        return MapRaster.build(new WorldGeography.RenderPage(1,new Page(new Key(0,px,pz),1,entries)));
    }
    public static void main(String[] args){
        var c=raster(-1,0,false,false);var e=raster(0,0,false,false);
        var s=raster(-1,1,false,false);var se=raster(0,1,false,false);
        var result=ContourLines.build(c,e,s,se,5);
        check(!result.overflow()&&!result.segments().isEmpty(),"ramp");
        for(var line:result.segments()){
            double expected=line.height()-64+32+.5;
            check(Math.abs(line.x0()-expected)<1e-7&&Math.abs(line.x1()-expected)<1e-7,"ground ramp exact level");
            check(line.z0()>=.5&&line.z1()<=32.5,"halo bounds");
        }
        var seam=ContourLines.build(c,e,s,se,32);
        check(seam.segments().stream().anyMatch(l->Math.abs(l.x0()-32.5)<1e-7)==false,"half-open seam owner");
        var flat=raster(0,0,false,true);
        check(ContourLines.build(flat,null,null,null,5).segments().isEmpty(),"flat no lines");
        var missing=raster(-1,0,true,false);
        for(var l:ContourLines.build(missing,e,s,se,1).segments())
            check(Math.max(l.x0(),l.x1())<=30.5+1e-7,"unknown no bridge");
        try{ContourLines.build(c,s,e,se,5);throw new AssertionError("halo");}catch(IllegalArgumentException expected){}
        check(ContourLines.interval(0)==5&&ContourLines.interval(10)==100,"adaptive levels");
        // A non-integral crossing lies in the owned cross-page square.
        var cross=ContourLines.build(c,e,s,se,7);
        check(cross.segments().stream().anyMatch(l->l.z0()>31.5||l.z1()>31.5),"south seam connected");
        // Display alpha rounds 1023/1024 up to 255, but the actual sample coverage is incomplete.
        var feature=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        var leaf=Entry.leaf(feature,70,70,0,100,100,100).stats();
        var partial=new Entry(Aggregate.merge(Collections.nCopies(1023,leaf)),null);
        var full=new Entry(Aggregate.merge(Collections.nCopies(1024,leaf)),null);
        var coarse=MapRaster.build(new WorldGeography.RenderPage(1,new Page(new Key(5,0,0),1,Map.of(0,partial,1,full))));
        check(coarse.coverage(0)==255&&!coarse.completeGround(0)&&coarse.completeGround(1),"exact known coverage not rounded alpha");
        System.out.println("PASS: ground rather than canopy, negative page ramp, observed halo, unknown exclusion, flat, interval");
    }
}
