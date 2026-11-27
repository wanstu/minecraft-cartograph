package com.wanstu.cartograph.v4;

/** Player heading is a horizontal world direction projected into screen space.
 * Minecraft yaw zero points south (+Z); positive yaw turns toward west (-X).
 * The glyph remains rigid, regardless of tilt or map zoom.
 */
public final class PlayerHeading {
    public static final int SIZE=20,RESERVE=12;
    private PlayerHeading(){}
    public record Direction(double x,double y){}
    public static Direction direction(MapCamera camera,double playerYaw){
        if(!Double.isFinite(playerYaw))throw new IllegalArgumentException("Player yaw");
        double relative=Math.toRadians((camera.yawDegrees()-playerYaw)%360);
        double x=Math.sin(relative),y=Math.cos(relative)*Math.sin(Math.toRadians(camera.elevationDegrees()));
        double length=Math.hypot(x,y);
        return new Direction(x/length,y/length);
    }
}
