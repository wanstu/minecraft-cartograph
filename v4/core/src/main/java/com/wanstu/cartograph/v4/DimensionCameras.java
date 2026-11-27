package com.wanstu.cartograph.v4;

import java.util.*;

/** One live world's bounded per-dimension camera history. Identity changes discard all positions. */
public final class DimensionCameras {
    public static final int CAPACITY=32;
    private Object world;
    private final LinkedHashMap<String,MapCamera> views=new LinkedHashMap<>(16,.75f,true);
    public void world(Object identity){if(world!=identity){views.clear();world=identity;}}
    public void remember(String dimension,MapCamera camera){
        if(world==null||camera==null)return;
        views.put(Objects.requireNonNull(dimension),camera);
        while(views.size()>CAPACITY){var it=views.keySet().iterator();it.next();it.remove();}
    }
    public MapCamera recall(String dimension){return views.get(dimension);}
    public int size(){return views.size();}
}
