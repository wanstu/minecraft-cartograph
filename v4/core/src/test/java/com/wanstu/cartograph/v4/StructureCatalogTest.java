package com.wanstu.cartograph.v4;

public final class StructureCatalogTest {
    public static void main(String[] args) {
        check("minecraft:village_plains", StructureCatalog.Category.SETTLEMENT);
        check("minecraft:village_desert", StructureCatalog.Category.SETTLEMENT);
        check("minecraft:village_savanna", StructureCatalog.Category.SETTLEMENT);
        check("minecraft:village_snowy", StructureCatalog.Category.SETTLEMENT);
        check("minecraft:village_taiga", StructureCatalog.Category.SETTLEMENT);
        check("minecraft:desert_pyramid", StructureCatalog.Category.LANDMARK);
        check("minecraft:jungle_pyramid", StructureCatalog.Category.LANDMARK);
        check("minecraft:igloo", StructureCatalog.Category.LANDMARK);
        check("minecraft:swamp_hut", StructureCatalog.Category.LANDMARK);
        check("minecraft:mansion", StructureCatalog.Category.LANDMARK);
        check("minecraft:pillager_outpost", StructureCatalog.Category.LANDMARK);
        check("minecraft:trail_ruins", StructureCatalog.Category.LANDMARK);
        check("minecraft:monument", StructureCatalog.Category.WATER);
        check("minecraft:ocean_ruin_cold", StructureCatalog.Category.WATER);
        check("minecraft:ocean_ruin_warm", StructureCatalog.Category.WATER);
        check("minecraft:shipwreck", StructureCatalog.Category.WATER);
        check("minecraft:shipwreck_beached", StructureCatalog.Category.WATER);
        check("minecraft:buried_treasure", StructureCatalog.Category.WATER);
        check("minecraft:mineshaft", StructureCatalog.Category.UNDERGROUND);
        check("minecraft:mineshaft_mesa", StructureCatalog.Category.UNDERGROUND);
        check("minecraft:stronghold", StructureCatalog.Category.UNDERGROUND);
        check("minecraft:ancient_city", StructureCatalog.Category.UNDERGROUND);
        check("minecraft:trial_chambers", StructureCatalog.Category.UNDERGROUND);
        check("minecraft:ruined_portal", StructureCatalog.Category.PORTAL);
        check("minecraft:ruined_portal_desert", StructureCatalog.Category.PORTAL);
        check("minecraft:ruined_portal_jungle", StructureCatalog.Category.PORTAL);
        check("minecraft:ruined_portal_mountain", StructureCatalog.Category.PORTAL);
        check("minecraft:ruined_portal_nether", StructureCatalog.Category.PORTAL);
        check("minecraft:ruined_portal_ocean", StructureCatalog.Category.PORTAL);
        check("minecraft:ruined_portal_swamp", StructureCatalog.Category.PORTAL);
        check("minecraft:fortress", StructureCatalog.Category.NETHER);
        check("minecraft:bastion_remnant", StructureCatalog.Category.NETHER);
        check("minecraft:nether_fossil", StructureCatalog.Category.NETHER);
        check("minecraft:end_city", StructureCatalog.Category.END);
        check("example:village_plains", StructureCatalog.Category.OTHER);
        check("minecraft:unknown_village", StructureCatalog.Category.OTHER);
        check("example:custom/ruin", StructureCatalog.Category.OTHER);
        System.out.println("StructureCatalogTest passed");
    }
    private static void check(String id, StructureCatalog.Category category) {
        var d = StructureCatalog.describe(id);
        if (d.category() != category || !d.fallback().equals(id)
                || !d.translationKey().equals("cartograph_v4.structure." + id.replace(':', '.')))
            throw new AssertionError(id + ": " + d);
    }
}
