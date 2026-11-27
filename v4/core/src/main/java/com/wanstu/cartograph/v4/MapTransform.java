package com.wanstu.cartograph.v4;

/** Affine camera coefficients for a local world-coordinate GPU batch. */
public final class MapTransform {
    private MapTransform(){}
    // Row-major 3x4 transform: local world X/Y/Z to GUI X/Y/depth.
    public static double[] coefficients(MapCamera camera,double originX,double originY,double originZ,
                                         int width,int height,double depthScale,boolean raised){
        var p=new MapProjection(camera);
        double yaw=Math.toRadians(camera.yawDegrees()),pitch=Math.toRadians(camera.elevationDegrees());
        double c=Math.cos(yaw),s=Math.sin(yaw),scale=camera.pixelsPerBlock();
        double sp=raised?Math.sin(pitch):1,cp=raised?Math.cos(pitch):0;
        return new double[]{
            c*scale,0,s*scale,width/2.0+p.screenX(originX,originZ),
            -s*sp*scale,-cp*scale,c*sp*scale,height/2.0+p.screenY(originX,originY,originZ),
            -s*cp*depthScale,raised?sp*depthScale:0,c*cp*depthScale,raised?100+p.depth(originX,originY,originZ)*depthScale:0
        };
    }
}
