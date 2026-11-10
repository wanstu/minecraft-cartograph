package com.wanstu.cartograph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;

/**
 * Immutable inventory state. All resource quantities use 1/10 point units:
 * 1.0x costs ten internal units, 0.1x costs one. No floating point debits.
 * Atlas identity is assigned on the first server-side operation.
 */
public record AtlasState(
        String id,
        int pages,
        int inkUnits,
        int pigmentUnits,
        int glowUnits,
        int redDyes,
        int yellowDyes,
        int blueDyes,
        boolean inkEnabled,
        boolean colorEnabled,
        boolean glowEnabled,
        boolean compassInstalled,
        int paperUnits,
        boolean paperEnabled
) {
    public static final int MAX_PAGES = 65536;
    public static final int MAX_UNITS = 100_000_000;
    public static final Codec<AtlasState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(AtlasState::id),
            Codec.INT.fieldOf("pages").forGetter(AtlasState::pages),
            Codec.INT.fieldOf("ink_units").forGetter(AtlasState::inkUnits),
            Codec.INT.fieldOf("pigment_units").forGetter(AtlasState::pigmentUnits),
            Codec.INT.fieldOf("glow_units").forGetter(AtlasState::glowUnits),
            Codec.INT.fieldOf("red_dyes").forGetter(AtlasState::redDyes),
            Codec.INT.fieldOf("yellow_dyes").forGetter(AtlasState::yellowDyes),
            Codec.INT.fieldOf("blue_dyes").forGetter(AtlasState::blueDyes),
            Codec.BOOL.fieldOf("ink_enabled").forGetter(AtlasState::inkEnabled),
            Codec.BOOL.fieldOf("color_enabled").forGetter(AtlasState::colorEnabled),
            Codec.BOOL.fieldOf("glow_enabled").forGetter(AtlasState::glowEnabled),
            Codec.BOOL.fieldOf("compass_installed").forGetter(AtlasState::compassInstalled),
            Codec.INT.optionalFieldOf("paper_units", 0).forGetter(AtlasState::paperUnits),
            Codec.BOOL.optionalFieldOf("paper_enabled", false).forGetter(AtlasState::paperEnabled)
    ).apply(i, AtlasState::new));

    public AtlasState {
        id = id == null ? "" : id;
        pages = Math.clamp(pages, 1, MAX_PAGES);
        inkUnits = Math.clamp(inkUnits, 0, MAX_UNITS);
        pigmentUnits = Math.clamp(pigmentUnits, 0, MAX_UNITS);
        glowUnits = Math.clamp(glowUnits, 0, MAX_UNITS);
        paperUnits = Math.clamp(paperUnits, 0, MAX_UNITS);
        redDyes = Math.clamp(redDyes, 0, 64);
        yellowDyes = Math.clamp(yellowDyes, 0, 64);
        blueDyes = Math.clamp(blueDyes, 0, 64);
    }

    public static AtlasState blank() {
        return new AtlasState("", 1, 0, 0, 0, 0, 0, 0, false, false, false, false, 0, false);
    }

    public AtlasState identified() {
        if (!id.isBlank()) return this;
        return new AtlasState(UUID.randomUUID().toString(), pages, inkUnits, pigmentUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, compassInstalled, paperUnits, paperEnabled);
    }

    public AtlasState extendOnePage() {
        if (pages >= MAX_PAGES) return this;
        return new AtlasState(id, pages + 1, inkUnits, pigmentUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, compassInstalled, paperUnits, paperEnabled);
    }

    private static int addUnits(int old, int units) {
        return (int) Math.min(MAX_UNITS, (long) old + Math.max(0L, (long) units));
    }

    public AtlasState fillPaperUnits(int units) {
        return new AtlasState(id, pages, inkUnits, pigmentUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, compassInstalled,
                addUnits(paperUnits, units), true);
    }

    public AtlasState payPaper(int costUnits) {
        if (!paperEnabled || costUnits < 1 || paperUnits < costUnits) return null;
        return new AtlasState(id, pages, inkUnits, pigmentUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, compassInstalled,
                paperUnits - costUnits, paperEnabled);
    }

    public AtlasState fillInkUnits(int units) {
        return new AtlasState(id, pages, addUnits(inkUnits, units), pigmentUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, true, colorEnabled, glowEnabled, compassInstalled, paperUnits, paperEnabled);
    }

    public AtlasState fillGlowUnits(int units) {
        return new AtlasState(id, pages, inkUnits, pigmentUnits, addUnits(glowUnits, units),
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, true, compassInstalled, paperUnits, paperEnabled);
    }

    public enum Primary { RED, YELLOW, BLUE }

    /** Complete RED+YELLOW+BLUE sets only; unmatched dyes remain stored. */
    public AtlasState addDye(Primary primary, int unitsPerSet) {
        if ((primary == Primary.RED && redDyes > 0)
                || (primary == Primary.YELLOW && yellowDyes > 0)
                || (primary == Primary.BLUE && blueDyes > 0)) return this;
        int r = redDyes + (primary == Primary.RED ? 1 : 0);
        int y = yellowDyes + (primary == Primary.YELLOW ? 1 : 0);
        int b = blueDyes + (primary == Primary.BLUE ? 1 : 0);
        int sets = Math.min(r, Math.min(y, b));
        return new AtlasState(id, pages, inkUnits, addUnits(pigmentUnits, sets * Math.max(0, unitsPerSet)), glowUnits,
                r - sets, y - sets, b - sets, inkEnabled, colorEnabled || sets > 0, glowEnabled, compassInstalled, paperUnits, paperEnabled);
    }

    public AtlasState withCompass(boolean installed) {
        return new AtlasState(id, pages, inkUnits, pigmentUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, installed, paperUnits, paperEnabled);
    }

    public AtlasState payInk(int costUnits) {
        if (!inkEnabled || costUnits < 0 || inkUnits < costUnits) return null;
        return new AtlasState(id, pages, inkUnits - costUnits, pigmentUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, compassInstalled, paperUnits, paperEnabled);
    }

    public AtlasState payPigment(int costUnits) {
        if (!colorEnabled || costUnits < 0 || pigmentUnits < costUnits) return null;
        return new AtlasState(id, pages, inkUnits, pigmentUnits - costUnits, glowUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, compassInstalled, paperUnits, paperEnabled);
    }

    public AtlasState payGlow(int costUnits) {
        if (!glowEnabled || costUnits < 0 || glowUnits < costUnits) return null;
        return new AtlasState(id, pages, inkUnits, pigmentUnits, glowUnits - costUnits,
                redDyes, yellowDyes, blueDyes, inkEnabled, colorEnabled, glowEnabled, compassInstalled, paperUnits, paperEnabled);
    }
}
