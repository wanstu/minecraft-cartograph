package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** North-up reference view planner. It controls presentation only, never geographic discovery. */
public final class MapViewport {
    private MapViewport(){}
    public record Plan(int lod,List<Key> keys){public Plan{keys=List.copyOf(keys);}}
    public static Plan plan(double x,double z,double scale,int width,int height,int maxPages){
        if(!Double.isFinite(x)||!Double.isFinite(z)||!Double.isFinite(scale)||scale<=0||
            Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||width<1||height<1||maxPages<4)
            throw new IllegalArgumentException("View bounds");
        int lod=Math.max(0,Math.min(10,(int)Math.ceil(Math.log(3/scale)/Math.log(2))));
        long minX,maxX,minZ,maxZ;
        while(true){
            double size=32.0*(1<<lod);
            minX=(long)Math.floor((x-width/(2*scale))/size);
            maxX=(long)Math.floor((x+width/(2*scale))/size);
            minZ=(long)Math.floor((z-height/(2*scale))/size);
            maxZ=(long)Math.floor((z+height/(2*scale))/size);
            if(minX<Integer.MIN_VALUE||maxX>Integer.MAX_VALUE||minZ<Integer.MIN_VALUE||maxZ>Integer.MAX_VALUE)
                throw new IllegalArgumentException("View coordinates");
            long nx=maxX-minX+1,nz=maxZ-minZ+1;
            if(nx<=maxPages&&nz<=maxPages&&nx*nz<=maxPages)break;
            if(lod==10)throw new IllegalArgumentException("View exceeds page budget");
            lod++;
        }
        double cx=x/(32.0*(1<<lod)),cz=z/(32.0*(1<<lod));
        List<Key> keys=new ArrayList<>();
        for(long iz=minZ;iz<=maxZ;iz++)for(long ix=minX;ix<=maxX;ix++)keys.add(new Key(lod,(int)ix,(int)iz));
        keys.sort(Comparator.comparingDouble(k->Math.hypot(k.x()+.5-cx,k.z()+.5-cz)));
        return new Plan(lod,keys);
    }
}
