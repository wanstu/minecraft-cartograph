package com.wanstu.cartograph.v4;

import java.util.Map;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class SurfaceAnchorTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static MapRaster raster(Key key,int slot,Entry entry){
        return MapRaster.build(new WorldGeography.RenderPage(1,new Page(key,1,Map.of(slot,entry))));
    }
    public static void main(String[] args){
        var feature=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        var leaf=Entry.leaf(feature,82,70,0,100,100,100);
        var key=new Key(0,-1,-1);
        var exact=raster(key,1023,leaf);
        var patch=new MapTileCoverage.Patch(key,0,0,1,0,0,1);
        var hit=SurfaceAnchor.sample(key,patch,exact,-1,-1);
        check(hit!=null&&hit.exact()&&hit.y()==82,"negative coordinate and actual surface, not ground");
        check(SurfaceAnchor.sample(key,patch,exact,-2,-1)==null,"unknown cell");
        check(SurfaceAnchor.sample(key,patch,exact,0,-1)==null,"outside target");
        var parent=new Key(1,-1,-1);
        long[] histogram=new long[FeatureDictionary.Terrain.values().length];
        histogram[FeatureDictionary.Terrain.ROCK.ordinal()]=4;
        var full=new Entry(new Aggregate(4,4,4,320,280,400,400,400,0,0,70,70,histogram),null);
        var coarse=raster(parent,1023,full);
        var fallback=new MapTileCoverage.Patch(parent,0,0,1,.5,.5,.5);
        var approx=SurfaceAnchor.sample(key,fallback,coarse,-1,-1);
        check(approx!=null&&!approx.exact()&&approx.y()==80,"coarse fallback marked approximate");
        var partial=raster(parent,1023,new Entry(leaf.stats(),null));
        check(SurfaceAnchor.sample(key,fallback,partial,-1,-1)==null,"partial coverage rejected");
        var missingHeight=new Entry(new Aggregate(4,3,4,240,280,400,400,400,0,0,70,70,histogram),null);
        check(SurfaceAnchor.sample(key,fallback,raster(parent,1023,missingHeight),-1,-1)==null,"unknown height despite full coverage");
        var child=new MapTileCoverage.Patch(key,.5,.5,.5,0,0,1);
        check(SurfaceAnchor.sample(parent,child,exact,-1,-1).exact(),"finer drawn child");
        check(SurfaceAnchor.sample(parent,child,exact,-33,-1)==null,"outside clipped child");
        for(double yaw:new double[]{0,45,90,180,270})for(double tilt:new double[]{30,45,60,90}){
            var camera=new MapCamera(0,64,0,3,yaw,tilt);
            var screen=new MapProjection(camera).project(-.5,hit.y(),-.5);
            var world=camera.unproject(screen);
            check(Math.abs(world.y()-82)<1e-8&&Math.abs(world.x()+.5)<1e-8,"camera anchor roundtrip");
        }
        System.out.println("PASS: actual surface anchor, negative coordinates, clipped fallback, partial/unknown rejection and camera projection");
    }
}
