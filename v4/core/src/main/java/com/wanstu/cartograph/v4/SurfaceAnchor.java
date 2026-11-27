package com.wanstu.cartograph.v4;

import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Samples only the observed surface of a currently drawn terrain patch. No IO or world access. */
public final class SurfaceAnchor {
    private SurfaceAnchor() {}
    public record Sample(float y, int lod) {
        public boolean exact(){return lod==0;}
    }
    public static Key keyAt(int lod,int x,int z){
        if(lod<0||lod>10)throw new IllegalArgumentException("LOD");
        int edge=32<<lod;
        return new Key(lod,Math.floorDiv(x,edge),Math.floorDiv(z,edge));
    }
    public static Sample sample(Key target,MapTileCoverage.Patch patch,MapRaster raster,int x,int z){
        if(!target.equals(keyAt(target.lod(),x,z))||!patch.source().equals(raster.key()))return null;
        int edge=32<<target.lod();
        double fx=(Math.floorMod(x,edge)+.5)/edge,fz=(Math.floorMod(z,edge)+.5)/edge;
        if(fx<patch.x()||fz<patch.z()||fx>=patch.x()+patch.size()||fz>=patch.z()+patch.size())return null;
        int sourceCell=1<<raster.key().lod(),sourceEdge=32*sourceCell;
        if(!raster.key().equals(keyAt(raster.key().lod(),x,z)))return null;
        int slot=Math.floorMod(z,sourceEdge)/sourceCell*32+Math.floorMod(x,sourceEdge)/sourceCell;
        float y=raster.surface(slot);
        return raster.completeSurface(slot)&&Float.isFinite(y)?new Sample(y,raster.key().lod()):null;
    }
}
