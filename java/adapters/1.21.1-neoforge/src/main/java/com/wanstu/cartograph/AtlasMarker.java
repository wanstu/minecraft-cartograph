package com.wanstu.cartograph;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** User-authored marker, stored server-side within a specific atlas and dimension. */
public record AtlasMarker(String id, String dimension, int x, int z, String name,
                          int icon, int color, boolean glowing, boolean glowEverPaid) {
    public static final int MAX_NAME = 24;
    public static final int ICON_COUNT = 6;
    public static final int COLOR_COUNT = 6;

    public AtlasMarker {
        if (id == null || id.isBlank() || dimension == null || dimension.isBlank())
            throw new IllegalArgumentException("Marker identity and dimension required");
        if (name == null || name.isBlank()) name = "标点";
        name = name.strip();
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        icon = Math.clamp(icon, 0, ICON_COUNT - 1);
        color = Math.clamp(color, 0, COLOR_COUNT - 1);
    }

    public static AtlasMarker newAt(String dimension, int x, int z, String name,
                                    int icon, int color, boolean glowing) {
        return new AtlasMarker(UUID.randomUUID().toString(), dimension, x, z,
                name, icon, color, glowing, glowing);
    }

    public CompoundTag save() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", id);
        nbt.putString("dim", dimension);
        nbt.putInt("x", x);
        nbt.putInt("z", z);
        nbt.putString("name", name);
        nbt.putInt("icon", icon);
        nbt.putInt("color", color);
        nbt.putBoolean("glow", glowing);
        nbt.putBoolean("paid_glow", glowEverPaid);
        return nbt;
    }

    public static AtlasMarker read(CompoundTag tag) {
        if (tag.getString("id").isBlank() || tag.getString("dim").isBlank()) return null;
        return new AtlasMarker(tag.getString("id"), tag.getString("dim"),
                tag.getInt("x"), tag.getInt("z"), tag.getString("name"),
                tag.getInt("icon"), tag.getInt("color"),
                tag.getBoolean("glow"), tag.getBoolean("paid_glow"));
    }
}
