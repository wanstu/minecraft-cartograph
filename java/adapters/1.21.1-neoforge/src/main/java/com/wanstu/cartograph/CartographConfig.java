package com.wanstu.cartograph;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-authoritative rates and refill amounts; 0x means no debits, not a free unlock. */
public final class CartographConfig {
    private CartographConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue INK_MULTIPLIER = BUILDER
            .comment("Per first-time black/white chunk survey, range 0..10. Zero means no debit, but ink must first be installed.")
            .defineInRange("cost.ink_multiplier", 1.0, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue PIGMENT_MULTIPLIER = BUILDER
            .comment("Per first-time chunk colorization, range 0..10. Zero still requires the primary-color upgrade.")
            .defineInRange("cost.pigment_multiplier", 1.0, 0.0, 10.0);
    public static final ModConfigSpec.DoubleValue GLOW_MULTIPLIER = BUILDER
            .comment("Per new glowing waypoint (or normal->glowing), base cost 1; 0 disables the debit, not the unlock.")
            .defineInRange("cost.glow_multiplier", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.IntValue INK_REFILL = BUILDER
            .comment("Points granted per vanilla ink sac.")
            .defineInRange("refill.ink", 128, 1, 100000);
    public static final ModConfigSpec.IntValue PIGMENT_REFILL = BUILDER
            .comment("Points granted per complete red+yellow+blue dye set.")
            .defineInRange("refill.pigment", 128, 1, 100000);
    public static final ModConfigSpec.IntValue GLOW_REFILL = BUILDER
            .comment("Points granted per glow ink sac.")
            .defineInRange("refill.glow", 32, 1, 100000);
    public static final ModConfigSpec SPEC = BUILDER.build();

    public static int inkCostUnits() { return toTenths(INK_MULTIPLIER.get()); }
    public static int pigmentCostUnits() { return toTenths(PIGMENT_MULTIPLIER.get()); }
    public static int glowCostUnits() { return toTenths(GLOW_MULTIPLIER.get()); }
    public static int toTenths(double multiplier) {
        return (int) Math.round(Math.clamp(multiplier, 0, 10) * 10.0);
    }
}
