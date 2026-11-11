package com.wanstu.cartograph;

import com.wanstu.cartograph.core.MapSheetCoverage;
import net.minecraft.nbt.CompoundTag;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Regression for a player teleporting thousands of blocks from their initial
 * paper sheet. The original paid area must stay intact, and a new empty map
 * must unlock a disjoint location, with NBT restart compatibility.
 */
public final class PaperCoverageSelfTest {
    static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        String dim = "minecraft:overworld";
        int initialX = 0, initialZ = 0;
        int distantX = Math.floorDiv(1733, 128);
        int distantZ = Math.floorDiv(9971, 128);
        check(!MapSheetCoverage.covers(initialX, initialZ, distantX, distantZ, 1, 512),
                "One initial paper sheet must not silently reveal another distant region");

        Class<AtlasWorldData.Entry> type = AtlasWorldData.Entry.class;
        Constructor<AtlasWorldData.Entry> ctor = type.getDeclaredConstructor(AtlasState.class, int.class);
        ctor.setAccessible(true);
        AtlasWorldData.Entry entry = ctor.newInstance(AtlasState.blank().identified(), 512);
        entry.setAnchor(dim, initialX, initialZ);
        check(entry.isCovered(dim, 0, 0), "Initial sheet at origin");
        check(!entry.isCovered(dim, distantX, distantZ), "No distant paper before spending");
        entry.placeExtraSheet(dim, distantX, distantZ);
        Field state = type.getDeclaredField("state");
        state.setAccessible(true);
        state.set(entry, entry.state().extendOnePage());
        check(entry.legacySheets() == 1 && entry.remoteSheets().size() == 1,
                "Remote addition must NOT silently grow the original spiral");
        check(entry.isCovered(dim, distantX, distantZ)
                        && entry.isCovered(dim, 0, 0),
                "Both original paper and new distant paper must remain visible");
        check(!entry.isCovered("minecraft:the_nether", distantX, distantZ),
                "Paper remains bound to correct dimension");

        Method save = type.getDeclaredMethod("saveEntry");
        Method load = type.getDeclaredMethod("loadEntry", CompoundTag.class);
        save.setAccessible(true);
        load.setAccessible(true);
        CompoundTag serialized = (CompoundTag) save.invoke(entry);
        AtlasWorldData.Entry restored = (AtlasWorldData.Entry) load.invoke(null, serialized);
        check(restored.isCovered(dim, 0, 0)
                        && restored.isCovered(dim, distantX, distantZ)
                        && restored.legacySheets() == 1 && restored.remoteSheets().size() == 1,
                "NBT reload must preserve both paper coverage regions");
        check(restored.surveyedCount() == 0 && restored.coloredCount() == 0,
                "Placing paper does not accidentally unlock ink or pigment");

        // Old 0.1.1 saves lack new fields. Their ALL purchased spiral sheets
        // must survive upgrade, not collapse to the first sheet.
        CompoundTag legacy = (CompoundTag) serialized.copy();
        legacy.remove("legacy_sheet_count");
        legacy.remove("remote_paper_sites");
        AtlasWorldData.Entry migrated = (AtlasWorldData.Entry) load.invoke(null, legacy);
        check(migrated.legacySheets() == 2 && migrated.remoteSheets().isEmpty(),
                "Legacy 0.1.1 sheet count migrated as existing spiral pages");
        System.out.println("PASS disjoint paper sheets, initial coverage, player-remote target, " +
                "world isolation, paper-only gates, NBT roundtrip and legacy migration");
    }
}
