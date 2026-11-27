package com.wanstu.cartograph.v4;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
public final class ContourDrapeTest{
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static MapRaster raster(int x,int z,boolean covered){
        var f=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        Map<Integer,Entry> entries=new HashMap<>();
        for(int i=0;i<1024;i++){
            int y=64+i%32;
            entries.put(i,Entry.leaf(f,y+(covered?10:0),y,0,100,100,100));
        }
        return MapRaster.build(new WorldGeography.RenderPage(1,new Page(new Key(0,x,z),1,entries)));
    }
    public static void main(String[] args){
        var c=raster(-1,0,false);var e=raster(0,0,false);
        var in=new ContourOverlayCache.Input(c,e,null,null);
        var s=new ContourLines.Segment(31.5,.5,32.5,1.5,75);
        var lines=ContourDrape.split(in,s);
        check(lines.size()==2,"split at simultaneous grid crossings");
        check(lines.getFirst().surface()==95&&lines.getLast().surface()==64,"actual exposed top heights");
        check(lines.getFirst().x1()==32&&lines.getLast().x0()==32,"exact cross page seam");
        var hidden=new ContourOverlayCache.Input(c,raster(0,0,true),null,null);
        check(ContourDrape.split(hidden,s).size()==1,"hidden ground omitted");
        check(ContourDrape.split(new ContourOverlayCache.Input(c,null,null,null),s).size()==1,"missing halo omitted");
        var three=ContourDrape.split(in,new ContourLines.Segment(1.5,1.8,2.5,2.8,65));
        check(three.size()==3,"separate X/Z crossings");
        for(var line:three)check(line.height()==65,"original contour value preserved");
        System.out.println("PASS: exposed surface drape, cell split, cross-page seam, hidden/unknown exclusion");
    }
}
