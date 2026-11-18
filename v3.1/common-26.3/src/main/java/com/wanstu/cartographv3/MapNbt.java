package com.wanstu.cartographv3;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Set;

final class MapNbt {
    private static final long[] EMPTY_LONGS = new long[0];
    private static final int[] EMPTY_INTS = new int[0];

    private MapNbt() {}

    static CompoundTag compound(CompoundTag tag, String key) { return tag.getCompoundOrEmpty(key); }
    static CompoundTag compound(ListTag list, int index) { return list.getCompoundOrEmpty(index); }
    static ListTag list(CompoundTag tag, String key, int type) { return tag.getListOrEmpty(key); }
    static long[] longs(CompoundTag tag, String key) { return tag.getLongArray(key).orElse(EMPTY_LONGS); }
    static int[] ints(CompoundTag tag, String key) { return tag.getIntArray(key).orElse(EMPTY_INTS); }
    static int intValue(CompoundTag tag, String key) { return tag.getIntOr(key, 0); }
    static int byteValue(CompoundTag tag, String key) { return tag.getByteOr(key, (byte) 0); }
    static String string(CompoundTag tag, String key) { return tag.getStringOr(key, ""); }
    static String string(ListTag tag, int index) { return tag.getStringOr(index, ""); }
    static Set<String> keys(CompoundTag tag) { return tag.keySet(); }
}
