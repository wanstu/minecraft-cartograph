package com.wanstu.cartograph.v4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

/** Prototype: equality-safe interning. IDs are local to this dictionary. */
public final class FeatureDictionary {
    public enum Terrain { OTHER, GRASS, FOREST, ROCK, SAND, WATER, SNOW, BUILT }
    public record Feature(String materialKey, String biomeKey, Terrain terrain) {
        public Feature {
            Objects.requireNonNull(materialKey);
            Objects.requireNonNull(biomeKey);
            Objects.requireNonNull(terrain);
        }
    }

    private final HashMap<Feature, Integer> ids = new HashMap<>();
    private final ArrayList<Feature> entries = new ArrayList<>();

    public int intern(Feature feature) {
        Objects.requireNonNull(feature);
        return ids.computeIfAbsent(feature, key -> {
            entries.add(key);
            return entries.size(); // zero is reserved for unknown
        });
    }

    public Feature get(int id) { return entries.get(id - 1); }
    public int size() { return entries.size(); }
}
