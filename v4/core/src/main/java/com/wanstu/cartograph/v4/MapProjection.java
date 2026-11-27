package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Projection and conservative visible bounds for both flat and raised maps. */
public final class MapProjection {
    private final MapCamera camera;
    private final double c,s,sp,cp,scale;
    public MapProjection(MapCamera camera){
        this.camera=Objects.requireNonNull(camera);
        double yaw=Math.toRadians(camera.yawDegrees()),pitch=Math.toRadians(camera.elevationDegrees());
        c=Math.cos(yaw);s=Math.sin(yaw);sp=camera.elevationDegrees()==90?1:Math.sin(pitch);
        cp=camera.elevationDegrees()==90?0:Math.cos(pitch);scale=camera.pixelsPerBlock();
    }
    public MapCamera.Screen project(double x,double y,double z){
        double dx=x-camera.x(),dy=y-camera.y(),dz=z-camera.z();
        double forward=-dx*s+dz*c;
        return new MapCamera.Screen((dx*c+dz*s)*scale,(forward*sp-dy*cp)*scale,forward*cp+dy*sp);
    }
    public double screenX(double x,double z){return ((x-camera.x())*c+(z-camera.z())*s)*scale;}
    public double screenY(double x,double y,double z){return ((-(x-camera.x())*s+(z-camera.z())*c)*sp-(y-camera.y())*cp)*scale;}
    public double depth(double x,double y,double z){return (-(x-camera.x())*s+(z-camera.z())*c)*cp+(y-camera.y())*sp;}
    public record Bounds(double minX,double minZ,double maxX,double maxZ){}
    public Bounds bounds(int width,int height,double minY,double maxY){
        if(width<1||height<1||!Double.isFinite(minY)||!Double.isFinite(maxY)||minY>maxY)throw new IllegalArgumentException("View bounds");
        double x0=Double.POSITIVE_INFINITY,z0=x0,x1=Double.NEGATIVE_INFINITY,z1=x1;
        for(double y:new double[]{minY,maxY})for(int a:new int[]{-1,1})for(int b:new int[]{-1,1}){
            var p=camera.atHeight(a*width/2.0,b*height/2.0,y);
            x0=Math.min(x0,p.x());x1=Math.max(x1,p.x());z0=Math.min(z0,p.z());z1=Math.max(z1,p.z());
        }
        return new Bounds(x0,z0,x1,z1);
    }
    /** Fixed draw budget for the prototype relief path. No chunk loads, even for height padding. */
    public MapViewport.Plan plan(int width,int height,double minY,double maxY,int maxPages){
        var bounds=bounds(width,height,minY,maxY);
        double effectiveScale=scale*sp;
        int lod=Math.max(0,Math.min(10,(int)Math.ceil(Math.log(3/effectiveScale)/Math.log(2))));
        long x0,x1,z0,z1;
        while(true){
            double size=32.0*(1<<lod);
            x0=(long)Math.floor(bounds.minX/size);x1=(long)Math.floor(bounds.maxX/size);
            z0=(long)Math.floor(bounds.minZ/size);z1=(long)Math.floor(bounds.maxZ/size);
            if(x0<Integer.MIN_VALUE||x1>Integer.MAX_VALUE||z0<Integer.MIN_VALUE||z1>Integer.MAX_VALUE)throw new IllegalArgumentException("View coordinates");
            long nx=x1-x0+1,nz=z1-z0+1;
            if(nx<=maxPages&&nz<=maxPages&&nx*nz<=maxPages)break;
            if(lod==10)throw new IllegalArgumentException("View exceeds page budget");
            lod++;
        }
        List<Key> keys=new ArrayList<>();
        for(long z=z0;z<=z1;z++)for(long x=x0;x<=x1;x++)keys.add(new Key(lod,(int)x,(int)z));
        double cx=camera.x()/(32.0*(1<<lod)),cz=camera.z()/(32.0*(1<<lod));
        keys.sort(Comparator.comparingDouble(k->Math.hypot(k.x()+.5-cx,k.z()+.5-cz)));
        return new MapViewport.Plan(lod,keys);
    }
}
