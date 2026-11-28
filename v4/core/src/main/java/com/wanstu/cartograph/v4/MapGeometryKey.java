package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Vertex dependencies only: flat quads depend on patch bounds/UV and slot, not pixel contents. */
public record MapGeometryKey(int lod,int x,int z,boolean raised,List<Face> faces){
    public record Face(Key target,MapTileCoverage.Patch patch,int slot,long generation){}
    public MapGeometryKey{
        faces=faces.stream().map(f->raised?f:new Face(f.target,f.patch,f.slot,0)).toList();
    }
}
