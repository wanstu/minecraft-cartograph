package com.wanstu.cartograph.v4;

import java.util.Map;
import java.util.Objects;

/** Presentation metadata only: no seed prediction or changes to the structure index. */
public final class StructureCatalog {
    public enum Category { SETTLEMENT, LANDMARK, WATER, UNDERGROUND, PORTAL, NETHER, END, OTHER }
    public static final int ALL_CATEGORIES=(1<<Category.values().length)-1;
    public static int bit(Category category){return 1<<category.ordinal();}
    public static Category category(String id){return VANILLA.getOrDefault(id,Category.OTHER);}
    public static boolean includes(int mask,String id){return (mask&bit(category(id)))!=0;}
    public record Description(Category category, String translationKey, String fallback) {}
    private static final Map<String, Category> VANILLA = Map.ofEntries(
        Map.entry("minecraft:village_plains", Category.SETTLEMENT),
        Map.entry("minecraft:village_desert", Category.SETTLEMENT),
        Map.entry("minecraft:village_savanna", Category.SETTLEMENT),
        Map.entry("minecraft:village_snowy", Category.SETTLEMENT),
        Map.entry("minecraft:village_taiga", Category.SETTLEMENT),
        Map.entry("minecraft:desert_pyramid", Category.LANDMARK),
        Map.entry("minecraft:jungle_pyramid", Category.LANDMARK),
        Map.entry("minecraft:igloo", Category.LANDMARK),
        Map.entry("minecraft:swamp_hut", Category.LANDMARK),
        Map.entry("minecraft:mansion", Category.LANDMARK),
        Map.entry("minecraft:pillager_outpost", Category.LANDMARK),
        Map.entry("minecraft:trail_ruins", Category.LANDMARK),
        Map.entry("minecraft:monument", Category.WATER),
        Map.entry("minecraft:ocean_ruin_cold", Category.WATER),
        Map.entry("minecraft:ocean_ruin_warm", Category.WATER),
        Map.entry("minecraft:shipwreck", Category.WATER),
        Map.entry("minecraft:shipwreck_beached", Category.WATER),
        Map.entry("minecraft:buried_treasure", Category.WATER),
        Map.entry("minecraft:mineshaft", Category.UNDERGROUND),
        Map.entry("minecraft:mineshaft_mesa", Category.UNDERGROUND),
        Map.entry("minecraft:stronghold", Category.UNDERGROUND),
        Map.entry("minecraft:ancient_city", Category.UNDERGROUND),
        Map.entry("minecraft:trial_chambers", Category.UNDERGROUND),
        Map.entry("minecraft:ruined_portal", Category.PORTAL),
        Map.entry("minecraft:ruined_portal_desert", Category.PORTAL),
        Map.entry("minecraft:ruined_portal_jungle", Category.PORTAL),
        Map.entry("minecraft:ruined_portal_mountain", Category.PORTAL),
        Map.entry("minecraft:ruined_portal_nether", Category.PORTAL),
        Map.entry("minecraft:ruined_portal_ocean", Category.PORTAL),
        Map.entry("minecraft:ruined_portal_swamp", Category.PORTAL),
        Map.entry("minecraft:fortress", Category.NETHER),
        Map.entry("minecraft:bastion_remnant", Category.NETHER),
        Map.entry("minecraft:nether_fossil", Category.NETHER),
        Map.entry("minecraft:end_city", Category.END)
    );

    private StructureCatalog() {}

    public static Description describe(String id) {
        Objects.requireNonNull(id);
        Category category = category(id);
        // Unknown and modded identifiers keep their namespace; never infer a vanilla category by substring.
        int separator = id.indexOf(':');
        String key = separator > 0 ? "cartograph_v4.structure." + id.replace(':', '.') : id;
        return new Description(category, key, id);
    }
}
