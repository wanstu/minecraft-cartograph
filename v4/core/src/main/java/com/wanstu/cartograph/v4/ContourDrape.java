package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.ContourLines.Segment;

/** Project ground isolines onto observed exposed stepped terrain, without crossing hidden ground.
 * Coordinates are in the center page's cell units; Y is world height.
 */
public final class ContourDrape {
    private ContourDrape(){}
    public record Line(double x0,double z0,double x1,double z1,float surface,int height){}
    public static List<Line> split(ContourOverlayCache.Input in,Segment s){
        double dx=s.x1()-s.x0(),dz=s.z1()-s.z0();
        if(Math.abs(dx)>1.000001||Math.abs(dz)>1.000001)throw new IllegalArgumentException("Expected local isoline");
        double[] cuts={0,1,1,1};int n=2;
        if(dx!=0){
            double edge=Math.floor(Math.min(s.x0(),s.x1()))+1,t=(edge-s.x0())/dx;
            if(t>1e-9&&t<1-1e-9)cuts[n++]=t;
        }
        if(dz!=0){
            double edge=Math.floor(Math.min(s.z0(),s.z1()))+1,t=(edge-s.z0())/dz;
            if(t>1e-9&&t<1-1e-9)cuts[n++]=t;
        }
        Arrays.sort(cuts,0,n);
        List<Line> out=new ArrayList<>(3);
        for(int i=1;i<n;i++){
            double a=cuts[i-1],b=cuts[i];if(b-a<1e-9)continue;
            double mid=(a+b)*.5;
            int x=(int)Math.floor(s.x0()+mid*dx),z=(int)Math.floor(s.z0()+mid*dz);
            if(x<0||x>32||z<0||z>32)continue;
            MapRaster raster=x==32?(z==32?in.southeast():in.east()):(z==32?in.south():in.center());
            if(raster==null)continue;
            int slot=(z&31)*32+(x&31);
            float y=raster.surface(slot),ground=raster.ground(slot);
            // The raw surface may be a roof, canopy or water above the relief ground.
            // Do not project a ground isoline onto that unrelated upper surface.
            if(!raster.completeGround(slot)||!Float.isFinite(y)||y!=ground)continue;
            out.add(new Line(s.x0()+a*dx,s.z0()+a*dz,s.x0()+b*dx,s.z0()+b*dz,y,s.height()));
        }
        return List.copyOf(out);
    }
}
