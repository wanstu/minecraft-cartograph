package com.wanstu.cartograph.v4;

public final class PlayerHeadingTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void near(double a,double b){check(Math.abs(a-b)<1e-9,"expected "+b+", got "+a);}
    public static void main(String[] args){
        var flat=new MapCamera(0,64,0,1,0,90);
        double[][] cardinal={{0,0,1},{90,-1,0},{180,0,-1},{270,1,0}};
        for(var a:cardinal){var d=PlayerHeading.direction(flat,a[0]);near(d.x(),a[1]);near(d.y(),a[2]);}
        int cases=0;
        for(double yaw:new double[]{0,45,90,135,180,225,270,315,359})
            for(double tilt:new double[]{30,45,60,90})
                for(double heading:new double[]{-720,-181,-90,0,12,45,90,135,179,180,225,270,315,359,720})
                    for(double scale:new double[]{.003,.01,1,4,16}){
                        var camera=new MapCamera(-1234,91,2345,scale,yaw,tilt);
                        var p=new MapProjection(camera);
                        double rad=Math.toRadians(heading);
                        var a=p.project(camera.x(),camera.y(),camera.z());
                        var b=p.project(camera.x()-Math.sin(rad)*10,camera.y(),camera.z()+Math.cos(rad)*10);
                        double dx=b.x()-a.x(),dy=b.y()-a.y(),len=Math.hypot(dx,dy);
                        var d=PlayerHeading.direction(camera,heading);
                        near(d.x(),dx/len);near(d.y(),dy/len);near(Math.hypot(d.x(),d.y()),1);
                        var zoomed=PlayerHeading.direction(new MapCamera(camera.x(),camera.y(),camera.z(),100,yaw,tilt),heading);
                        near(d.x(),zoomed.x());near(d.y(),zoomed.y());
                        check(PlayerHeading.RESERVE>=Math.ceil(Math.hypot(6,6)*PlayerHeading.SIZE/16)+1,"rotated glyph fits reservation");
                        cases++;
                    }
        byte[] outer=MapIcons.raster(MapIcons.Icon.PLAYER_BORDER),inner=MapIcons.raster(MapIcons.Icon.PLAYER_FILL);
        int solid=0;
        for(int i=0;i<outer.length;i++){
            check((inner[i]&255)<=(outer[i]&255),"fill contained in outline");
            if((inner[i]&255)==255)solid++;
        }
        check(solid>12,"solid arrow not a line icon");
        check((outer[1*16+7]&255)>0&&(outer[1*16+7]&255)>(inner[1*16+7]&255),"tip outline");
        var hud=new MapHudLayout(640,360,false);var layout=new MapLabelLayout();layout.begin(hud);
        check(layout.reserve(320,180,PlayerHeading.RESERVE,PlayerHeading.RESERVE),"player priority reservation");
        check(!layout.reserve(330,180,8,8),"other marker cannot overlap arrow");
        System.out.println("PASS: "+cases+" projected heading/zoom cases, four cardinal directions, solid nested masks and collision reservation");
    }
}
