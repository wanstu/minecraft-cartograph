package com.wanstu.cartograph;

import net.neoforged.neoforge.common.ModConfigSpec;
import com.wanstu.cartograph.core.MapSheetCoverage;

/** Server-wide material settings. The wire and ledger use integer tenths. */
public final class CartographConfig {
    private CartographConfig() {}
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final String[] RESOURCES = {"ink", "pigment", "glow", "paper"};
    private static final ModConfigSpec.DoubleValue[] COST = multipliers("cost", 0.1);
    private static final ModConfigSpec.DoubleValue[] INPUT = multipliers("input", 4.0);
    private static final ModConfigSpec.BooleanValue[] PERMANENT = permanents();
    // The old 1.0 paper input multiplier now yields four uses of an expensive vanilla blank map.
    private static final int[] BASE_POINTS = {128, 128, 32, 4};
    private static final ModConfigSpec.IntValue PAGE_SIDE =
            BUILDER.defineInRange("coverage.blocks_per_blank_map", MapSheetCoverage.DEFAULT_BLOCKS,
                    MapSheetCoverage.MIN_BLOCKS, MapSheetCoverage.MAX_BLOCKS);
    private static final ModConfigSpec.IntValue WORKERS =
            BUILDER.defineInRange("performance.disk_reader_threads", 4, 2, 8);
    private static final ModConfigSpec.IntValue BUDGET =
            BUILDER.defineInRange("performance.server_budget_ms", 4, 2, 8);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private static ModConfigSpec.DoubleValue[] multipliers(String group, double fallback) {
        ModConfigSpec.DoubleValue[] out = new ModConfigSpec.DoubleValue[RESOURCES.length];
        for (int i = 0; i < out.length; i++)
            out[i] = BUILDER.defineInRange(group + "." + RESOURCES[i] + "_multiplier", i == 3 ? 1.0 : fallback, 0.1, 10.0);
        return out;
    }
    private static ModConfigSpec.BooleanValue[] permanents() {
        ModConfigSpec.BooleanValue[] out = new ModConfigSpec.BooleanValue[RESOURCES.length];
        for (int i = 0; i < out.length; i++) out[i] = BUILDER.define("permanent." + RESOURCES[i], false);
        return out;
    }
    static int workerThreads() { return WORKERS.get(); }
    static int mainThreadBudgetMs() { return BUDGET.get(); }
    static void setPerformance(int workers, int budget) {
        WORKERS.set(Math.clamp(workers, 2, 8));
        BUDGET.set(Math.clamp(budget, 2, 8));
        SPEC.save();
    }
    static int pageSideBlocks() {
        int configured = PAGE_SIDE.get();
        return MapSheetCoverage.valid(configured) ? configured : MapSheetCoverage.DEFAULT_BLOCKS;
    }
    static int cost(int material) { return (int) Math.round(COST[material].get() * 10.0); }
    static int input(int material) { return (int) Math.round(INPUT[material].get() * 10.0); }
    static int refillUnits(int material) { return refillUnits(material, input(material)); }
    static int refillUnits(int material, int inputTenths) { return BASE_POINTS[material] * inputTenths; }
    static boolean permanent(int material) { return PERMANENT[material].get(); }

    static MapNetwork.MaterialRates rates() {
        int flags = 0;
        for (int i = 0; i < RESOURCES.length; i++) if (permanent(i)) flags |= 1 << i;
        return new MapNetwork.MaterialRates(cost(0), cost(1), cost(2), cost(3),
                input(0), input(1), input(2), input(3), flags, pageSideBlocks());
    }
    static void update(MapNetwork.MaterialRates rates) {
        for (int i = 0; i < RESOURCES.length; i++) {
            COST[i].set(rates.cost(i) / 10.0);
            INPUT[i].set(rates.input(i) / 10.0);
            PERMANENT[i].set(rates.permanent(i));
        }
        PAGE_SIDE.set(rates.pageSideBlocks());
        SPEC.save();
    }
}
