package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** The visual styles never alter observations and use the same weighted facts at every LOD. */
public final class MapStylePaletteTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static Entry leaf(FeatureDictionary.Terrain terrain,int y,int depth){
        return Entry.leaf(new FeatureDictionary.Feature("test:"+terrain.name().toLowerCase(Locale.ROOT),"test:biome",terrain),y+depth,y,depth,17000,26000,40000);
    }
    private static MapRaster page(Key key,Map<Integer,Entry> values){
        return MapRaster.build(new WorldGeography.RenderPage(1,new Page(key,1,values)));
    }
    private static int channel(int c,int shift){return (c>>>shift)&255;}
    public static void main(String[] args){
        var values=new HashMap<Integer,Entry>();int n=0;
        for(var terrain:FeatureDictionary.Terrain.values())values.put(n++,leaf(terrain,64,0));
        values.put(8,leaf(FeatureDictionary.Terrain.WATER,62,1));
        values.put(9,leaf(FeatureDictionary.Terrain.WATER,45,18));
        var raster=page(new Key(0,-7,9),values);
        var translated=page(new Key(0,100,-50),values);
        for(var style:MapRaster.Style.values()){
            for(int i=0;i<10;i++){
                check(raster.argb(style,i)==translated.argb(style,i),"world-position-independent color");
                check(raster.coverage(i)==255&&channel(raster.argb(style,i),24)==255,"full observation");
                check(raster.surface(i)==translated.surface(i)&&raster.ground(i)==translated.ground(i),"height unchanged");
            }
            check(raster.argb(style,10)==0&&raster.coverage(10)==0,"unknown transparent");
            for(int shift:new int[]{0,8,16})check(channel(raster.argb(style,9),shift)<=channel(raster.argb(style,8),shift),"observed water depth monotonic");
            check(channel(raster.argb(style,9),0)<channel(raster.argb(style,8),0),"water depth contrast");
        }
        for(int i=0;i<8;i++){
            Set<Integer> colors=new HashSet<>();
            for(var style:MapRaster.Style.values())colors.add(raster.argb(style,i));
            check(colors.size()==5,"all five styles distinct for terrain "+i);
        }
        for(var style:new MapRaster.Style[]{MapRaster.Style.SATELLITE_ANCIENT,MapRaster.Style.HAND_MODERN,MapRaster.Style.HAND_ANCIENT,MapRaster.Style.HAND_ANIME}){
            int water=raster.argb(style,8);check(channel(water,0)>channel(water,16),"water retains blue hue");
        }
        var grass=leaf(FeatureDictionary.Terrain.GRASS,64,0);
        var forest=leaf(FeatureDictionary.Terrain.FOREST,64,0);
        var mixed=Aggregate.merge(List.of(grass.stats(),grass.stats(),forest.stats(),forest.stats()));
        var partial=Aggregate.merge(List.of(grass.stats(),forest.stats()));
        var coarse=page(new Key(1,0,0),Map.of(0,new Entry(mixed,null),1,new Entry(partial,null),
            2,new Entry(Aggregate.merge(List.of(grass.stats(),leaf(FeatureDictionary.Terrain.GRASS,112,0).stats())),null),
            3,new Entry(Aggregate.merge(List.of(grass.stats(),grass.stats())),null)));
        for(var style:MapRaster.Style.values()){
            check(coarse.coverage(0)==255&&coarse.coverage(1)==128,"exact LOD coverage");
            check((coarse.argb(style,0)&0xffffff)==(coarse.argb(style,1)&0xffffff),"normalized weighted color not dependent on observed fraction");
        }
        for(var style:List.of(MapRaster.Style.HAND_MODERN,MapRaster.Style.HAND_ANCIENT,MapRaster.Style.HAND_ANIME))
            check(channel(coarse.argb(style,2),8)<channel(coarse.argb(style,3),8),"observed coarse ground relief contrast");
        System.out.println("PASS: five style palettes, observed water depth, unknown/partial coverage, mixed LOD and world-position invariance");
    }
}
