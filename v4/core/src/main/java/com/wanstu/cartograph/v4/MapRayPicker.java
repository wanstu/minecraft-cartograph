package com.wanstu.cartograph.v4;

/** Ray intersection against the same axis-aligned faces emitted by the map renderer. */
public final class MapRayPicker {
    public record Hit(MapCamera.World world,int lod,boolean side,double depth){}
    private final MapCamera.World start,dir;
    private final boolean raised;
    private Hit best;
    public MapRayPicker(MapCamera camera,double sx,double sy){
        raised=camera.elevationDegrees()!=90;
        start=camera.unproject(new MapCamera.Screen(sx,sy,0));
        double yaw=Math.toRadians(camera.yawDegrees()),pitch=Math.toRadians(camera.elevationDegrees());
        double cp=raised?Math.cos(pitch):0;
        dir=new MapCamera.World(-Math.sin(yaw)*cp,raised?Math.sin(pitch):1,Math.cos(yaw)*cp);
    }
    /** Conservative ray/page bounds test; a rejected page cannot contain a hit. */
    public boolean mayHit(double x0,double z0,double x1,double z1,double minY,double maxY){
        if(!Double.isFinite(minY)||!Double.isFinite(maxY)||minY>maxY)return false;
        double t0=(minY-start.y())/dir.y(),t1=(maxY-start.y())/dir.y();
        if(best!=null&&t1<=best.depth())return false;
        double ax=start.x()+t0*dir.x(),bx=start.x()+t1*dir.x();
        double az=start.z()+t0*dir.z(),bz=start.z()+t1*dir.z();
        double eps=1e-7;
        return Math.max(ax,bx)>=x0-eps&&Math.min(ax,bx)<=x1+eps&&
            Math.max(az,bz)>=z0-eps&&Math.min(az,bz)<=z1+eps;
    }
    /** Test a textured face. Its source texel must have observed coverage. */
    public void face(MapRaster raster,double x0,double y0,double z0,double x1,double y1,double z1,
                     double u0,double v0,double u1,double v1){
        boolean side=y0!=y1;
        double t;
        if(!side){
            t=(y0-start.y())/dir.y();
        }else if(x0==x1){
            if(Math.abs(dir.x())<1e-12)return;
            t=(x0-start.x())/dir.x();
        }else{
            if(Math.abs(dir.z())<1e-12)return;
            t=(z0-start.z())/dir.z();
        }
        if(best!=null&&t<=best.depth())return;
        double x=start.x()+t*dir.x(),y=start.y()+t*dir.y(),z=start.z()+t*dir.z();
        double eps=1e-7;
        if(x<Math.min(x0,x1)-eps||x>Math.max(x0,x1)+eps||y<Math.min(y0,y1)-eps||y>Math.max(y0,y1)+eps||
           z<Math.min(z0,z1)-eps||z>Math.max(z0,z1)+eps)return;
        double u=side?u0:u0+(u1-u0)*(x-x0)/(x1-x0);
        double v=side?v0:v0+(v1-v0)*(z-z0)/(z1-z0);
        int ix=Math.max(0,Math.min(31,(int)Math.floor(u))),iz=Math.max(0,Math.min(31,(int)Math.floor(v)));
        if(raster.coverage(iz*32+ix)==0)return;
        if(!raised){
            y=raster.surface(iz*32+ix);if(!Double.isFinite(y))return;
        }
        best=new Hit(new MapCamera.World(x,y,z),raster.key().lod(),side,t);
    }
    public Hit result(){return best;}
}
