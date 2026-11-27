package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.function.Predicate;

/** Render-owned visibility memo; unchecked labels are never shown. */
public final class ContourLabelVisibility {
    public static final int MAX_CHECKS_PER_FRAME=2, MAX_CANDIDATES=64;
    public record Anchor(double x,double y,double z,int height) {}
    private final Map<Anchor,Boolean> results=new HashMap<>();
    private MapCamera camera;
    private Object terrain;
    private int remaining;
    public void begin(MapCamera camera,Object terrain){
        if(!Objects.equals(this.camera,camera)||!Objects.equals(this.terrain,terrain)){
            results.clear();this.camera=camera;this.terrain=terrain;
        }
        remaining=MAX_CHECKS_PER_FRAME;
    }
    public boolean visible(Anchor anchor,Predicate<Anchor> check){
        Boolean cached=results.get(anchor);
        if(cached!=null)return cached;
        if(remaining==0||results.size()>=MAX_CANDIDATES)return false;
        remaining--;
        boolean visible=check.test(anchor);results.put(anchor,visible);return visible;
    }
    public void clear(){results.clear();camera=null;terrain=null;remaining=0;}
}
