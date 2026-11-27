package com.wanstu.cartograph.v4;
import java.util.Map;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
public final class MapRayPickerTest{
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static MapRaster raster(){
        var f=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
        var e=Entry.leaf(f,70,70,0,100,100,100);
        return MapRaster.build(new WorldGeography.RenderPage(1,new Page(new Key(0,0,0),1,Map.of(0,e,1,e,32,e,33,e))));
    }
    public static void main(String[] args){
        var r=raster();
        for(double yaw:new double[]{0,45,90,180,270})for(double angle:new double[]{30,45,60,90}){
            var camera=new MapCamera(0,64,0,3,yaw,angle);
            var target=new MapCamera.World(.5,70,.5);
            var screen=camera.project(target);
            var picker=new MapRayPicker(camera,screen.x(),screen.y());
            check(picker.mayHit(0,0,2,2,64,80),"page broad phase retains hit");
            check(!picker.mayHit(10000,10000,10002,10002,64,80),"page broad phase rejects distant");
            picker.face(r,0,70,0,2,70,2,0,0,2,2);
            var hit=picker.result();
            check(hit!=null&&Math.abs(hit.world().x()-.5)<1e-8&&Math.abs(hit.world().z()-.5)<1e-8&&hit.world().y()==70,"top pick");
        }
        var flat=new MapCamera(0,64,0,1,0,90);
        var unknown=new MapRayPicker(flat,5,5);
        unknown.face(r,0,0,0,32,0,32,0,0,32,32);
        check(unknown.result()==null,"transparent unknown");
        var known=new MapRayPicker(flat,.5,.5);
        known.face(r,0,0,0,32,0,32,0,0,32,32);
        check(known.result()!=null&&known.result().world().y()==70,"flat returns observed height");
        var tilted=new MapCamera(0,64,0,2,0,45);
        var s=tilted.project(new MapCamera.World(.5,69,1));
        var side=new MapRayPicker(tilted,s.x(),s.y());
        side.face(r,0,70,1,1,68,1,.5,.5,.5,.5);
        check(side.result()!=null&&side.result().side()&&Math.abs(side.result().world().y()-69)<1e-8,"side intersection");
        // A closer top face must win independent of submission order.
        var p=tilted.project(new MapCamera.World(.5,70,.5));
        var front=new MapRayPicker(tilted,p.x(),p.y());
        front.face(r,0,69,-1,2,69,1,0,0,2,2);
        front.face(r,0,70,0,2,70,2,0,0,2,2);
        check(front.result()!=null&&front.result().world().y()==70,"closest face");
        System.out.println("PASS: 20 camera orientations, top/side hit, nearest depth, unknown rejection and flat observed height");
    }
}
