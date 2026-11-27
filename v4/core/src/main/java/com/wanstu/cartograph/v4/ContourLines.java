package com.wanstu.cartograph.v4;

import java.util.*;

/** Ground-height isolines, in local page cell units, never interpolate across unknown cells.
 * Squares are owned by their north-west sample; east/south halo supplies the seam.
 * Fixed NW-SE triangulation resolves ambiguous saddles deterministically.
 */
public final class ContourLines {
    public static final int LIMIT=32768;
    public record Segment(double x0,double z0,double x1,double z1,int height){}
    public record Result(List<Segment> segments,boolean overflow){
        public Result{segments=List.copyOf(segments);}
    }
    private ContourLines(){}
    public static int interval(int lod){
        if(lod<0||lod>10)throw new IllegalArgumentException("LOD");
        return lod==0?5:lod==1?10:lod==2?20:lod<=4?50:100;
    }
    private record Sample(double x,double z,float y){}
    private static Sample sample(MapRaster c,MapRaster e,MapRaster s,MapRaster se,int x,int z){
        var page=x==32?(z==32?se:e):(z==32?s:c);
        if(page==null)return null;
        int i=(z&31)*32+(x&31);
        float h=page.ground(i);
        return page.completeGround(i)&&Float.isFinite(h)?new Sample(x+.5,z+.5,h):null;
    }
    private static void validate(MapRaster c,MapRaster p,int dx,int dz){
        if(p==null)return;
        var k=c.key();var n=p.key();
        if(k.lod()!=n.lod()||k.x()+dx!=n.x()||k.z()+dz!=n.z())
            throw new IllegalArgumentException("Incorrect contour halo");
    }
    public static Result build(MapRaster center,MapRaster east,MapRaster south,MapRaster southeast,int step){
        Objects.requireNonNull(center);
        if(step<1)throw new IllegalArgumentException("Interval");
        validate(center,east,1,0);validate(center,south,0,1);validate(center,southeast,1,1);
        List<Segment> out=new ArrayList<>();
        for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            Sample a=sample(center,east,south,southeast,x,z),b=sample(center,east,south,southeast,x+1,z),
                c=sample(center,east,south,southeast,x+1,z+1),d=sample(center,east,south,southeast,x,z+1);
            if(a==null||b==null||c==null||d==null)continue;
            if(!triangle(a,b,c,step,out)||!triangle(a,c,d,step,out))return new Result(List.of(),true);
        }
        return new Result(out,false);
    }
    private static boolean triangle(Sample a,Sample b,Sample c,int step,List<Segment> out){
        double min=Math.min(a.y,Math.min(b.y,c.y)),max=Math.max(a.y,Math.max(b.y,c.y));
        if(min==max)return true;
        long start=(long)Math.ceil(min/step),end=(long)Math.ceil(max/step)-1;
        if(end-start>LIMIT)return false;
        for(long level=start;level<=end;level++){
            long h=level*step;if(h<Integer.MIN_VALUE||h>Integer.MAX_VALUE)return false;
            // A consistent half-open classification avoids duplicated plateau boundaries.
            double y=Math.nextUp((double)h);
            Sample p=intersect(a,b,y),q=intersect(b,c,y),r=intersect(c,a,y);
            Sample first=p!=null?p:q,second=r!=null?r:(p!=null?q:null);
            if(first==null||second==null||Math.hypot(first.x-second.x,first.z-second.z)<1e-9)continue;
            if(out.size()>=LIMIT)return false;
            out.add(new Segment(first.x,first.z,second.x,second.z,(int)h));
        }
        return true;
    }
    private static Sample intersect(Sample a,Sample b,double y){
        if((a.y>y)==(b.y>y))return null;
        double t=(y-a.y)/(b.y-a.y);
        return new Sample(a.x+t*(b.x-a.x),a.z+t*(b.z-a.z),(float)y);
    }
}
