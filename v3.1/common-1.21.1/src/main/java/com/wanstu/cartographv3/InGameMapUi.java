package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class InGameMapUi {
    static final int BACKGROUND = 0xff17383c;
    static final int TOP = 0xff111a20;
    static final int PANEL = 0xf8182228;
    static final int FIELD = 0xff202b32;
    static final int HOVER = 0xff293840;
    static final int LINE = 0xff46555f;
    static final int TEXT = 0xfff2f5f7;
    static final int MUTED = 0xffaab5bc;
    static final int SOFT = 0xff84939b;
    static final int ACCENT = 0xfff3c94f;
    static final int BLUE = 0xff68b5f7;
    static final int DANGER = 0xffdf6d68;
    static final float SMALL_TEXT = .75f;

    private static final ResourceLocation ICONS = ResourceLocation.fromNamespaceAndPath(
            "cartograph_v3", "textures/gui/map-icons.png");
    private static final String[] ICON_NAMES = "map pin layers settings info close check edit trash teleport add locate reset chevron copy frame display performance keyboard wrench home village mansion temple outpost mine portal ship city fortress cave mountain tree water player cross dot save desert-pyramid jungle-temple swamp-hut igloo ocean-monument ruined-portal shipwreck ocean-ruin buried-treasure trail-ruins trial-chambers ancient-city stronghold nether-fortress bastion-remnant nether-fossil end-city".split(" ");
    private static final Map<String, Integer> INDEX = new HashMap<>();
    static {
        for (int i = 0; i < ICON_NAMES.length; i++) INDEX.put(ICON_NAMES[i], i);
    }

    record Box(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean at(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
        boolean overlaps(Box other) {
            return x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y;
        }
    }

    private record Action(Box box, Runnable run) {}

    private final List<Action> actions = new ArrayList<>();
    private final List<Box> blocking = new ArrayList<>();
    private final GuiGraphics g;
    final Font font;
    private final int mouseX;
    private final int mouseY;
    private final double guiUnitsPerLayoutUnit;
    private final float fontScale;

    InGameMapUi(GuiGraphics graphics, Font font, int mouseX, int mouseY,
                double guiUnitsPerLayoutUnit, float fontScale) {
        this.g = graphics;
        this.font = font;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.guiUnitsPerLayoutUnit = guiUnitsPerLayoutUnit;
        this.fontScale = fontScale;
    }

    boolean hovered(Box box) { return box.at(mouseX, mouseY); }
    boolean blocked(double x, double y) { return blocking.stream().anyMatch(box -> box.at(x, y)); }

    void clearActions() {
        actions.clear();
        blocking.clear();
    }

    void block(Box box) { blocking.add(box); }

    void action(Box box, Runnable run) { actions.add(new Action(box, run)); }

    boolean click(double x, double y) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            Action action = actions.get(i);
            if (action.box.at(x, y)) {
                action.run.run();
                return true;
            }
        }
        return false;
    }

    void fill(Box box, int color) { g.fill(box.x, box.y, box.right(), box.bottom(), color); }

    void clip(Box box) {
        // GuiGraphics accepts GUI coordinates independently of the current pose.
        g.enableScissor((int) Math.floor(box.x * guiUnitsPerLayoutUnit),
                (int) Math.floor(box.y * guiUnitsPerLayoutUnit),
                (int) Math.ceil(box.right() * guiUnitsPerLayoutUnit),
                (int) Math.ceil(box.bottom() * guiUnitsPerLayoutUnit));
    }

    void unclip() { g.disableScissor(); }

    void border(Box box, int color) { g.renderOutline(box.x, box.y, box.w, box.h, color); }

    void panel(Box box) {
        fill(new Box(box.x + 2, box.y + 3, box.w, box.h), 0x48000000);
        fill(box, PANEL);
        border(box, LINE);
        block(box);
    }

    void text(String value, int x, int y, int color) {
        if (fontScale == 1) g.drawString(font, value, x, y, color, false);
        else scaledText(value, x, y, color, fontScale);
    }

    int textWidth(String value) { return textWidth(value, 1); }
    int textWidth(String value, float styleScale) {
        return (int) Math.ceil(font.width(value) * styleScale * fontScale);
    }

    int textHeight() { return textHeight(1); }
    int textHeight(float styleScale) {
        return (int) Math.ceil(font.lineHeight * styleScale * fontScale);
    }

    private String clipped(String value, int room, float scale) {
        int available = (int) (room / scale);
        if (available <= 0) return "";
        if (font.width(value) <= available) return value;
        int ellipsis = font.width("…");
        if (available < ellipsis) return "";
        return font.plainSubstrByWidth(value, available - ellipsis) + "…";
    }

    void smallText(String value, int x, int y, int room, int color) {
        text(clipped(value, room, fontScale), x, y, color);
    }

    void smallText(String value, int x, int y, int room, int color, float styleScale) {
        float scale = styleScale * fontScale;
        scaledText(clipped(value, room, scale), x, y, color, scale);
    }

    void center(String value, Box box, int color) {
        String label = clipped(value, box.w - 3, fontScale);
        text(label, box.x + (box.w - textWidth(label)) / 2,
                box.y + (box.h - textHeight()) / 2, color);
    }

    void center(String value, Box box, int color, float styleScale) {
        float scale = styleScale * fontScale;
        String label = clipped(value, box.w - 3, scale);
        float x = box.x + (box.w - font.width(label) * scale) / 2;
        float y = box.y + (box.h - font.lineHeight * scale) / 2;
        scaledText(label, x, y, color, scale);
    }

    private void scaledText(String value, float x, float y, int color, float scale) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);
        g.drawString(font, value, 0, 0, color, false);
        pose.popPose();
    }

    void divider(int x, int y, int width) { fill(new Box(x, y, width, 1), LINE); }

    void button(Box box, String title, boolean selected, Runnable run) {
        fill(box, selected ? 0xff314754 : hovered(box) ? HOVER : FIELD);
        border(box, selected ? BLUE : LINE);
        center(title, box, TEXT);
        action(box, run);
    }

    void iconButton(Box box, String name, int color, Runnable run) {
        if (hovered(box)) fill(box, HOVER);
        int size = Math.min(12, Math.min(box.w, box.h) - 2);
        glyph(name, box.x + (box.w - size) / 2, box.y + (box.h - size) / 2, size, color);
        action(box, run);
    }

    void check(Box box, boolean checked) {
        fill(box, checked ? ACCENT : TOP);
        border(box, checked ? ACCENT : LINE);
        if (checked) glyph("check", box.x + 1, box.y + 1, box.w - 2, TOP);
    }

    void glyph(MapMarkerStore.Icon icon, int x, int y, int size, int color) {
        glyph(iconName(icon), x, y, size, color);
    }

    static String iconName(MapMarkerStore.Icon icon) {
        if (icon == null) return "pin";
        return switch (icon) {
            case CAMP -> "home";
            case LAKE, RIVER, LARGE_RIVER, SMALL_RIVER, WATERFALL -> "water";
            case MOUNTAIN_RANGE, HILL -> "mountain";
            default -> icon.name().toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }

    static List<MapMarkerStore.Icon> selectableIcons() {
        return java.util.Arrays.stream(MapMarkerStore.Icon.values())
                .filter(icon -> INDEX.containsKey(iconName(icon))).toList();
    }

    void glyph(String name, int x, int y, int size, int color) {
        int index = INDEX.getOrDefault(name, INDEX.get("pin"));
        int u = index % 8 * 32;
        int v = index / 8 * 32;
        g.setColor((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f,
                (color & 255) / 255f, (color >>> 24) / 255f);
        g.blit(ICONS, x, y, size, size, (float) u, (float) v, 32, 32, 256, 256);
        g.setColor(1, 1, 1, 1);
    }
}
