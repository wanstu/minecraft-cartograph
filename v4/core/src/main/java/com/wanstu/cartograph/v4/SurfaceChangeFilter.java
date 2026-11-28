package com.wanstu.cartograph.v4;

/** Conservative post-mutation rejection for the facts captured by the surface mapper.
 * Retains a one-block margin, and never ignores unknown heights or an uncertain surface type.
 */
public final class SurfaceChangeFilter {
    private SurfaceChangeFilter(){}
    public static boolean belowSurface(int y,int top,int relief,int minY){
        return top>=minY&&relief>=minY&&(long)y<(long)Math.min(top,relief)-1;
    }
    public static boolean irrelevant(int y,int top,int relief,int floor,int minY,boolean definitelyNonWater){
        if(!belowSurface(y,top,relief,minY))return false;
        return definitelyNonWater||floor>=minY&&(long)y<(long)floor-1;
    }
}
