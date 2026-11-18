package com.wanstu.cartographv3;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Set;

final class MapNbt {
    private MapNbt() {}

    static CompoundTag compound(CompoundTag tag, String key) { return tag.getCompound(key); }
    static CompoundTag compound(ListTag list, int index) { return list.getCompound(index); }
    static ListTag list(CompoundTag tag, String key, int type) { return tag.getList(key, type); }
    static long[] longs(CompoundTag tag, String key) { return tag.getLongArray(key); }
    static int[] ints(CompoundTag tag, String key) { return tag.getIntArray(key); }
    static int intValue(CompoundTag tag, String key) { return tag.getInt(key); }
    static int byteValue(CompoundTag tag, String key) { return tag.getByte(key); }
    static String string(CompoundTag tag, String key) { return tag.getString(key); }
    static String string(ListTag tag, int index) { return tag.getString(index); }
    static Set<String> keys(CompoundTag tag) { return tag.getAllKeys(); }
}
