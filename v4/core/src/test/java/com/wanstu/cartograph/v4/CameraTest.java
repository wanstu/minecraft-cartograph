package com.wanstu.cartograph.v4;

public final class CameraTest {
    static void close(double a,double b) {
        if (Math.abs(a-b)>1e-7) throw new AssertionError(a+" != "+b);
    }
    static void world(MapCamera.World a,MapCamera.World b) {
        close(a.x(),b.x());close(a.y(),b.y());close(a.z(),b.z());
    }
    public static void main(String[] args) {
        var origin = new MapCamera(100,64,-100,2,0,90);
        var pt = new MapCamera.World(120,98,-80);
        var flat = origin.project(pt);
        close(flat.x(),40);close(flat.y(),40);
        close(origin.project(new MapCamera.World(120,0,-80)).y(),flat.y());
        int cases=0;
        for(int yaw=0;yaw<=360;yaw+=15) for(int angle=30;angle<=90;angle+=5) {
            var c=origin.withAngles(yaw,angle);
            for(var p:new MapCamera.World[]{pt,new MapCamera.World(-200,0,600),new MapCamera.World(29999999,-64,-29999999)}){
                var s=c.project(p);
                world(p,c.unproject(s));
                world(p,c.atHeight(s.x(),s.y(),p.y()));
                var zoom=c.zoomAt(5.75,p).project(p);
                close(zoom.x(),s.x());close(zoom.y(),s.y());
                cases++;
            }
        }
        System.out.println("PASS: north-up orthographic parity; "+cases+" projection/picking-plane/anchored-zoom cases");
    }
}
