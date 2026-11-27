package com.wanstu.cartograph.v4;

import java.util.EnumSet;

/** Render-owned visibility state, independent of geography, style and camera. */
public final class MapLayers {
    public enum Layer { CONTOURS, MARKERS, STRUCTURES, REGIONS }
    private final EnumSet<Layer> visible=EnumSet.of(Layer.MARKERS,Layer.STRUCTURES,Layer.REGIONS);
    private int categories=StructureCatalog.ALL_CATEGORIES;
    public boolean visible(Layer layer){return visible.contains(layer);}
    public void toggle(Layer layer){if(!visible.remove(layer))visible.add(layer);}
    public int categoryMask(){return categories;}
    public boolean categoryVisible(StructureCatalog.Category category){return (categories&StructureCatalog.bit(category))!=0;}
    public void toggleCategory(StructureCatalog.Category category){categories^=StructureCatalog.bit(category);}
    public void setAllCategories(boolean enabled){categories=enabled?StructureCatalog.ALL_CATEGORIES:0;}
    public boolean structuresActive(){return visible(Layer.STRUCTURES)&&categories!=0;}
}
