package com.wanstu.cartograph.v4;
import java.util.*;

/** User-authored horizontal named regions, with canonical inclusive block bounds. */
public record NamedRegion(UUID id,int minX,int minZ,int maxX,int maxZ,String name,String description,int color){
    public NamedRegion{
        Objects.requireNonNull(id);
        name=Objects.requireNonNull(name).strip();description=Objects.requireNonNull(description);
        if(minX>maxX||minZ>maxZ||Math.abs((long)minX)>30_000_000||Math.abs((long)minZ)>30_000_000||
            Math.abs((long)maxX)>30_000_000||Math.abs((long)maxZ)>30_000_000||name.isEmpty()||name.length()>80||
            name.chars().anyMatch(Character::isISOControl)||description.length()>512||(color&0xff000000)!=0)
            throw new IllegalArgumentException("Invalid named region");
    }
    public static NamedRegion between(UUID id,int x0,int z0,int x1,int z1,String name,String description,int color){
        return new NamedRegion(id,Math.min(x0,x1),Math.min(z0,z1),Math.max(x0,x1),Math.max(z0,z1),name,description,color);
    }
    public double centerX(){return (minX+(double)maxX+1)/2;}
    public double centerZ(){return (minZ+(double)maxZ+1)/2;}
    public long area(){return (maxX-(long)minX+1)*(maxZ-(long)minZ+1);}
    public boolean contains(double x,double z){return x>=minX&&x<maxX+1.0&&z>=minZ&&z<maxZ+1.0;}
    public boolean intersects(MapProjection.Bounds b){return maxX+1.0>b.minX()&&minX<=b.maxX()&&maxZ+1.0>b.minZ()&&minZ<=b.maxZ();}
    /** Smallest containing region wins; UUID provides deterministic tie-breaking. */
    public static NamedRegion select(List<NamedRegion> regions,double x,double z){
        return regions.stream().filter(r->r.contains(x,z)).min(Comparator.comparingLong(NamedRegion::area).thenComparing(NamedRegion::id)).orElse(null);
    }
}
