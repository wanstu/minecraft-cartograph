package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.ContourLabelVisibility.Anchor;

public final class ContourLabelVisibilityTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        var memo=new ContourLabelVisibility();
        var camera=new MapCamera(0,64,0,3,0,45);
        var a=new Anchor(.5,70,.5,70);
        var b=new Anchor(2.5,75,2.5,75);
        var c=new Anchor(4.5,80,4.5,80);
        int[] calls={0};
        java.util.function.Predicate<Anchor> visible=anchor->{calls[0]++;return !anchor.equals(b);};
        memo.begin(camera,List.of("mesh1"));
        check(memo.visible(a,visible)&&!memo.visible(b,visible)&&!memo.visible(c,visible)&&calls[0]==2,"bounded new checks, unchecked hidden");
        memo.begin(camera,List.of("mesh1"));
        check(memo.visible(a,visible)&&!memo.visible(b,visible)&&memo.visible(c,visible)&&calls[0]==3,"cached hidden and visible results");
        for(int i=0;i<1000;i++){
            memo.begin(camera,List.of("mesh1"));
            check(memo.visible(a,visible)&&!memo.visible(b,visible)&&memo.visible(c,visible),"stable camera");
        }
        check(calls[0]==3,"warm frames no ray checks");
        memo.begin(camera,List.of("mesh2"));
        check(memo.visible(a,visible)&&calls[0]==4,"mesh change invalidation");
        memo.begin(camera.withAngles(90,45),List.of("mesh2"));
        check(memo.visible(a,visible)&&calls[0]==5,"camera change invalidation");
        memo.clear();memo.begin(camera,List.of("mesh2"));
        check(memo.visible(a,visible)&&calls[0]==6,"labels rebuild invalidation");

        var raster=MapRayPickerTest.raster();
        // Check front/behind separation in every raised camera orientation.
        for(double yaw:new double[]{0,45,90,180,270})for(double tilt:new double[]{30,45,60}){
            var cam=new MapCamera(0,64,0,3,yaw,tilt);
            var anchor=cam.project(new MapCamera.World(.5,70,.5));
            double threshold=anchor.depth()+.0001;
            var ray=new MapRayPicker(cam,anchor.x(),anchor.y(),threshold);
            ray.face(raster,0,70,0,2,70,2,0,0,2,2);
            check(ray.result()==null,"own surface not occluded");
            check(!ray.mayHit(0,0,2,2,68,70),"behind page culled");
            var front=cam.atHeight(anchor.x(),anchor.y(),80);
            double x=front.x(),z=front.z();
            check(ray.mayHit(x-.1,z-.1,x+.1,z+.1,80,80),"front page retained");
            ray.face(raster,x-.1,80,z-.1,x+.1,80,z+.1,0,0,1,1);
            check(ray.result()!=null&&ray.result().depth()>threshold,"front terrain occludes anchor");
        }
        System.out.println("PASS: bounded label checks, 1000 stable frames, camera/mesh invalidation and front/behind terrain in 15 orientations");
    }
}
