package com.wanstu.cartograph;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cartograph's full-screen atlas, adapted from preview/cartograph-fullscreen-hifi.html.
 * The terrain itself fills the window; the toolbar, independent floating panels,
 * context menu and modal editors never reserve permanent map margins.
 *
 * All changes to balances, compass, markers, map exports and settings are
 * requests to the authoritative server, never local client mutations.
 */
public final class AtlasScreen extends Screen {
    private static final int PAPER = 0xffe6d8b7;
    private static final int DARK = 0xff1b2d24;
    private static final int PANEL_BG = 0xf620352b;
    private static final int GOLD = 0xffe7cf9e;
    private static final int CREAM = 0xfff2e6cd;
    private static final int MUTED = 0xffb0bfae;
    private static final int[] COLORS = {
            0xf7f2df, 0xe99357, 0xeeca53, 0x70b67b, 0x72a7d1, 0xcf89c0
    };
    private static final String[] ICONS = {"住处", "旗帜", "矿区", "地标", "港口", "其他"};
    private static final String[] TOOLS = {"图册", "标点", "图层", "设置", "居中", "信息", "×"};
    private static final String[] MATERIALS = {"墨囊", "发光墨囊", "红色染料", "黄色染料", "蓝色染料"};
    private static final String[] RATE_NAMES = {"墨水", "颜料", "发光墨水"};
    private static final String[] LAYER_NAMES = {"自然结构", "地表道路", "地表铁路", "自定义标点"};
    private static final String[] LAYER_HINTS = {
            "仅显示真实生成且可确认的地表结构", "原版实际地表道路",
            "原版铁轨和 Create 地表轨道", "本地图册内已保存的标点"
    };

    private enum Panel { NONE, ATLAS, MARKERS, LAYERS, INFO }
    private enum Dialog { NONE, MARKER, SETTINGS }
    private enum Context { NONE, MAP, MARKER, MATERIAL }

    private static boolean drawStructures = true;
    private static boolean drawRoads = true;
    private static boolean drawRails = true;
    private static boolean drawWaypoints = true;

    private double viewX, viewZ, zoom, zoomPixels = 2.35;
    // 1.55 physical pixels per UI unit, matching the original HTML prototype.
    private float chromeScale = 1.0f;
    private int vw() { return Math.max(1, (int) Math.ceil(width / chromeScale)); }
    private int vh() { return Math.max(1, (int) Math.ceil(height / chromeScale)); }
    private double ux(double x) { return x / chromeScale; }
    private double uy(double y) { return y / chromeScale; }
    private boolean initialized, draggingMap;
    private int frameTicks, actionRefreshTicks;
    private int hoverX = -1, hoverY = -1;
    private Panel panel = Panel.NONE;
    private Dialog dialog = Dialog.NONE;
    private Context context = Context.NONE;
    private int contextX, contextY, pendingX, pendingZ;
    private int markerScroll;
    private String markerFilter = "";
    private EditBox markerSearch;
    private AtlasMarker selectedMarker;
    private EditBox markerName;
    private final EditBox[] refillInput = new EditBox[3];
    private int chosenIcon, chosenColor;
    private boolean chosenGlow;
    private int[] rates = {10, 10, 10};
    private String notice = "";
    private boolean noticeError;
    private int noticeUntil;

    /** Called only after the server has actually acknowledged an operation. */
    public void feedback(boolean ok, String message) {
        noticeError = !ok;
        toast((ok ? "✓ " : "！") + message);
        if (ok) {
            reloadVisible(false);
            CartographClient.queue(Math.floorDiv((int) viewX, 128),
                    Math.floorDiv((int) viewZ, 128), true);
        }
    }

    /** First coverage answer gives the UI the purchased page grid. */
    public void refreshVisible() { reloadVisible(false); }

    public AtlasScreen() {
        super(Component.translatable("screen.cartograph.atlas"));
    }

    @Override
    protected void init() {
        super.init();
        double guiScale = Math.max(1.0, minecraft.getWindow().getGuiScale());
        chromeScale = (float) (1.55 / guiScale);
        zoom = zoomPixels / guiScale;
        if (!initialized && minecraft != null && minecraft.player != null) {
            viewX = minecraft.player.getBlockX();
            viewZ = minecraft.player.getBlockZ();
            initialized = true;
        }
        if (dialog == Dialog.MARKER) setupMarkerWidget();
        else if (dialog == Dialog.SETTINGS) setupSettingsWidgets();
        else if (panel == Panel.MARKERS) setupMarkerSearch();
        reloadVisible(false);
    }

    private int screenMidX() { return width / 2; }
    private int screenMidY() { return height / 2; }

    private int toWorldX(double guiX) {
        double raw = viewX + (guiX - screenMidX()) / zoom;
        return (int) Math.floor(Math.clamp(raw, -30_000_000.0, 30_000_000.0));
    }
    private int toWorldZ(double guiY) {
        double raw = viewZ + (guiY - screenMidY()) / zoom;
        return (int) Math.floor(Math.clamp(raw, -30_000_000.0, 30_000_000.0));
    }
    private int toScreenX(double world) { return (int) Math.round(screenMidX() + (world - viewX) * zoom); }
    private int toScreenZ(double world) { return (int) Math.round(screenMidY() + (world - viewZ) * zoom); }
    private int firstPageX() { return Math.floorDiv(toWorldX(0), 128); }
    private int lastPageX() { return Math.floorDiv(toWorldX(width), 128); }
    private int firstPageZ() { return Math.floorDiv(toWorldZ(0), 128); }
    private int lastPageZ() { return Math.floorDiv(toWorldZ(height), 128); }

    private void reloadVisible(boolean force) {
        int cx = Math.floorDiv(toWorldX(screenMidX()), 128);
        int cz = Math.floorDiv(toWorldZ(screenMidY()), 128);
        int count = 0;
        for (int ring = 0; ring <= 10 && count < 64; ring++) {
            for (int z = Math.max(firstPageZ(), cz - ring); z <= Math.min(lastPageZ(), cz + ring); z++) {
                for (int x = Math.max(firstPageX(), cx - ring); x <= Math.min(lastPageX(), cx + ring); x++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != ring) continue;
                    CartographClient.queue(x, z, force);
                    if (++count >= 64) return;
                }
            }
        }
    }

    @Override
    public void tick() {
        if (++frameTicks % 200 == 0) {
            // Do not enqueue all visible pages again while an earlier scan
            // is incomplete. Refresh one centered page at a time.
            CartographClient.queue(Math.floorDiv((int) Math.floor(viewX), 128),
                    Math.floorDiv((int) Math.floor(viewZ), 128), true);
        }
        if (actionRefreshTicks > 0 && --actionRefreshTicks == 0) reloadVisible(true);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        hoverX = mx;
        hoverY = my;
        renderTerrain(g);
        int sx = (int) ux(mx), sy = (int) uy(my);
        g.pose().pushPose();
        g.pose().scale(chromeScale, chromeScale, 1);
        try {
            renderToolbar(g, sx, sy);
            if (panel != Panel.NONE && dialog == Dialog.NONE) renderFloatingPanel(g, sx, sy);
            if (context != Context.NONE && dialog == Dialog.NONE) renderContext(g, sx, sy);
            if (dialog != Dialog.NONE) renderDialog(g, sx, sy);
            else renderSurveyBadge(g, mx, my);
            // No fixed footer: map coordinates and status belong in optional popovers.
            if (!notice.isBlank() && frameTicks <= noticeUntil) {
                int w = Math.min(vw() - 20, font.width(notice) + 30);
                int x = (vw() - w) / 2;
                g.fill(x, vh() - 45, x + w, vh() - 20,
                        noticeError ? 0xee673b31 : 0xee1b2c23);
                g.drawCenteredString(font, shortText(notice, w - 12),
                        vw() / 2, vh() - 36, GOLD);
            }
            super.render(g, sx, sy, partialTick);
        } finally {
            g.pose().popPose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Minecraft Screen.render() would otherwise blur every layer already drawn.
    }

    private void renderTerrain(GuiGraphics g) {
        g.fill(0, 0, width, height, PAPER);
        g.enableScissor(0, 0, width, height);
        int drawn = 0;
        for (int pz = firstPageZ(); pz <= lastPageZ() && drawn < 100; pz++) {
            for (int px = firstPageX(); px <= lastPageX() && drawn < 100; px++) {
                drawn++;
                ResourceLocation texture = CartographClient.texture(px, pz);
                MapNetwork.Page page = CartographClient.page(px, pz);
                if (texture == null || page == null || !page.inRange()) continue;
                int x = toScreenX((long) px * 128);
                int y = toScreenZ((long) pz * 128);
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale((float) zoom, (float) zoom, 1);
                g.blit(texture, 0, 0, 0, 0, 128, 128, 128, 128);
                g.pose().popPose();
                if (drawStructures || drawWaypoints) renderMarkers(g, page);
            }
        }
        if (minecraft != null && minecraft.player != null) {
            int x = minecraft.player.getBlockX(), z = minecraft.player.getBlockZ();
            MapNetwork.Page playerPage = CartographClient.page(Math.floorDiv(x, 128), Math.floorDiv(z, 128));
            if (playerPage != null && playerPage.inRange() && playerPage.compass()) {
                int sx = toScreenX(x), sy = toScreenZ(z);
                g.pose().pushPose();
                g.pose().translate(sx, sy, 0);
                g.pose().scale(chromeScale, chromeScale, 1);
                g.fill(-4, -4, 5, 5, 0xff8a382c);
                g.fill(-2, -2, 3, 3, 0xffffe1b6);
                g.pose().popPose();
            }
        }
        g.disableScissor();
    }

    private void renderMarkers(GuiGraphics g, MapNetwork.Page page) {
        if (drawStructures) {
            for (SurfaceStructure poi : page.structures()) {
                int x = toScreenX(poi.x()), y = toScreenZ(poi.z());
                if (x < -60 || x >= width + 60 || y < -30 || y >= height + 30) continue;
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(chromeScale, chromeScale, 1);
                g.fill(-6, -6, 7, 7, 0xff334236);
                g.fill(-4, -4, 5, 5, 0xffd3b879);
                g.drawString(font, "◆", -3, -4, 0xff23342c, false);
                if (zoomPixels >= 2.0) label(g, structureTitle(poi.type()), 9, -5);
                g.pose().popPose();
            }
        }
        if (drawWaypoints) {
            for (AtlasMarker marker : page.markers()) {
                int x = toScreenX(marker.x()), y = toScreenZ(marker.z());
                if (x < -60 || x >= width + 60 || y < -30 || y >= height + 30) continue;
                int color = COLORS[Math.clamp(marker.color(), 0, COLORS.length - 1)];
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(chromeScale, chromeScale, 1);
                if (marker.glowing()) {
                    g.fill(-8, -8, 9, 9, 0x88ffe3a4);
                    g.fill(-6, -6, 7, 7, 0xffcda968);
                }
                renderIcon(g, 0, 0, marker.icon(), color);
                if (zoomPixels >= 2.0) label(g, marker.name(), 10, -5);
                g.pose().popPose();
            }
        }
    }

    private void label(GuiGraphics g, String value, int x, int y) {
        String name = shortText(value, 105);
        int w = font.width(name);
        g.fill(x - 2, y - 2, x + w + 3, y + 11, 0xdaf3e8ce);
        g.drawString(font, name, x, y, 0xff2c392f, false);
    }

    private void renderIcon(GuiGraphics g, int x, int y, int icon, int color) {
        int dark = 0xff243229;
        int ink = 0xff000000 | color;
        g.fill(x - 5, y - 5, x + 6, y + 6, dark);
        g.fill(x - 4, y - 4, x + 5, y + 5, 0xfff1e5c9);
        switch (icon) {
            case 0 -> {
                g.fill(x - 3, y - 1, x + 4, y + 4, ink);
                g.fill(x - 4, y - 2, x + 5, y - 1, ink);
                g.fill(x - 2, y - 3, x + 3, y - 2, ink);
                g.fill(x, y + 1, x + 2, y + 4, dark);
            }
            case 1 -> {
                g.fill(x - 3, y - 4, x - 2, y + 4, dark);
                g.fill(x - 2, y - 4, x + 4, y, ink);
            }
            case 2 -> {
                for (int d = -3; d <= 3; d++) {
                    g.fill(x + d, y + d, x + d + 1, y + d + 1, ink);
                    g.fill(x - d, y + d, x - d + 1, y + d + 1, dark);
                }
            }
            case 3 -> {
                for (int d = 0; d <= 3; d++) {
                    g.fill(x - (3 - d), y - d, x + (3 - d) + 1, y - d + 1, ink);
                    g.fill(x - (3 - d), y + d, x + (3 - d) + 1, y + d + 1, ink);
                }
            }
            case 4 -> {
                g.fill(x - 1, y - 3, x + 2, y + 3, ink);
                g.fill(x - 4, y + 1, x - 3, y + 3, ink);
                g.fill(x + 3, y + 1, x + 4, y + 3, ink);
                g.fill(x - 3, y + 3, x + 4, y + 4, ink);
            }
            default -> {
                g.fill(x - 3, y - 2, x + 4, y + 3, ink);
                g.fill(x - 1, y - 1, x + 2, y + 2, dark);
            }
        }
    }

    private static String structureTitle(String type) {
        if (type.startsWith("minecraft:village_")) return "村庄";
        return switch (type) {
            case "minecraft:desert_pyramid" -> "沙漠神殿";
            case "minecraft:jungle_temple" -> "丛林神庙";
            case "minecraft:pillager_outpost" -> "掠夺者前哨站";
            case "minecraft:woodland_mansion" -> "林地府邸";
            case "minecraft:swamp_hut" -> "沼泽小屋";
            case "minecraft:igloo" -> "雪屋";
            default -> type.startsWith("minecraft:ruined_portal") ? "废弃传送门" : "地表建筑";
        };
    }

    private int toolW() { return vw() < 590 ? 35 : 38; }
    private int toolY() { return 16; }
    private int toolbarWidth() { return 7 * toolW() + 6 * 4 + 12; }
    private int toolbarX() { return vw() - toolbarWidth() - 18; }

    private void renderToolbar(GuiGraphics g, int mx, int my) {
        int tw = toolW(), tx = toolbarX(), ty = toolY();
        if (vw() >= 520) {
            g.fill(14, 13, vw() >= 650 ? 180 : 102, 49, 0xed1b2e25);
            g.fill(18, 18, 43, 43, 0xff405a45);
            g.renderItem(new ItemStack(CartographMod.ATLAS.get()), 22, 22);
            g.drawString(font, vw() >= 650 ? "绘图师 · 世界地图" : "绘图师", 50, 28, CREAM);
        }
        g.fill(tx - 6, ty - 6, tx + toolbarWidth(), ty + 45, 0xeb1b2c23);
        for (int i = 0; i < TOOLS.length; i++) {
            int bx = tx + i * (tw + 4);
            boolean active = (i == 0 && panel == Panel.ATLAS)
                    || (i == 1 && panel == Panel.MARKERS)
                    || (i == 2 && panel == Panel.LAYERS)
                    || (i == 5 && panel == Panel.INFO)
                    || (i == 3 && dialog == Dialog.SETTINGS);
            boolean hovered = contains(mx, my, bx, ty, tw, 37);
            g.fill(bx, ty, bx + tw, ty + 37,
                    active ? 0xff627a60 : hovered ? 0xff50664e : 0xff30483b);
            // Match the high-fidelity HTML SVG toolbar: neutral thin line
            // icons, not giant vanilla item sprites magnified by GUI scale.
            renderToolGlyph(g, bx + tw / 2, ty + 11, i,
                    active ? 0xfff6d9a3 : 0xffd8e1d4);
            g.drawCenteredString(font, TOOLS[i], bx + tw / 2, ty + 23,
                    active ? 0xffffffff : CREAM);
        }
    }

    private void hLine(GuiGraphics g, int x, int y, int len, int c) {
        g.fill(x, y, x + len, y + 1, c);
    }
    private void vLine(GuiGraphics g, int x, int y, int len, int c) {
        g.fill(x, y, x + 1, y + len, c);
    }

    /** At 1.55 real pixels per unit, 14px icons match the HTML's 19px SVGs. */
    private void renderToolGlyph(GuiGraphics g, int cx, int cy, int kind, int c) {
        int x = cx - 7, y = cy - 7;
        switch (kind) {
            case 0 -> { // atlas / open book
                vLine(g, x, y + 2, 12, c);
                vLine(g, x + 13, y + 2, 12, c);
                vLine(g, x + 6, y + 3, 11, c);
                hLine(g, x + 1, y + 2, 4, c);
                hLine(g, x + 8, y + 2, 5, c);
                hLine(g, x + 1, y + 12, 4, c);
                hLine(g, x + 8, y + 12, 5, c);
            }
            case 1 -> { // map pin with dot
                hLine(g, x + 4, y, 6, c);
                hLine(g, x + 2, y + 2, 2, c); hLine(g, x + 10, y + 2, 2, c);
                vLine(g, x + 1, y + 4, 5, c); vLine(g, x + 12, y + 4, 5, c);
                hLine(g, x + 3, y + 10, 2, c); hLine(g, x + 9, y + 10, 2, c);
                g.fill(cx - 1, cy - 3, cx + 2, cy, c);
                vLine(g, cx, cy + 4, 6, c);
            }
            case 2 -> { // layers
                hLine(g, x + 2, y + 3, 10, c);
                hLine(g, x, y + 5, 14, c);
                hLine(g, x + 2, y + 9, 10, c);
                hLine(g, x, y + 11, 14, c);
                hLine(g, x + 2, y + 13, 10, c);
            }
            case 3 -> { // three adjustment sliders
                hLine(g, x, y + 3, 14, c);
                hLine(g, x, y + 7, 14, c);
                hLine(g, x, y + 11, 14, c);
                g.fill(x + 3, y + 1, x + 6, y + 6, c);
                g.fill(x + 9, y + 5, x + 12, y + 10, c);
                g.fill(x + 5, y + 9, x + 8, y + 14, c);
            }
            case 4 -> { // recenter / crosshair
                vLine(g, cx, y, 14, c); hLine(g, x, cy, 14, c);
                hLine(g, x + 3, y + 2, 8, c);
                hLine(g, x + 3, y + 12, 8, c);
                vLine(g, x + 2, y + 3, 9, c);
                vLine(g, x + 12, y + 3, 9, c);
            }
            case 5 -> { // information
                hLine(g, x + 4, y, 6, c);
                hLine(g, x + 4, y + 13, 6, c);
                vLine(g, x + 1, y + 3, 8, c);
                vLine(g, x + 12, y + 3, 8, c);
                g.fill(cx, y + 3, cx + 2, y + 5, c);
                vLine(g, cx + 1, y + 7, 4, c);
            }
            default -> { // dismiss
                for (int j = 0; j < 12; j++) {
                    g.fill(x + 1 + j, y + 1 + j, x + 2 + j, y + 2 + j, c);
                    g.fill(x + 12 - j, y + 1 + j, x + 13 - j, y + 2 + j, c);
                }
            }
        }
    }

    private int panelW() { return Math.min(218, vw() - 20); }
    private int panelX() { return vw() - panelW() - 18; }
    private int panelY() { return 66; }
    private int panelH() {
        return switch (panel) {
            case ATLAS -> Math.min(vh() - panelY() - 12,
                    vh() < 365 ? 247 : vh() < 402 ? 274 : 332);
            case MARKERS -> Math.min(vh() - panelY() - 12, 352);
            case LAYERS -> Math.min(vh() - panelY() - 12, 285);
            case INFO -> Math.min(vh() - panelY() - 12, 242);
            default -> 0;
        };
    }
    private boolean compact() { return vh() < 402; }
    private boolean tiny() { return vh() < 365; }
    private boolean contains(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void card(GuiGraphics g, int x, int y, int w, int h, String heading, String subtitle) {
        g.fill(x - 4, y + 4, x + w + 4, y + h + 7, 0x7715201b);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xff6c765f);
        g.fill(x, y, x + w, y + h, PANEL_BG);
        // HTML prototype: subdued eyebrow, title and description, not a
        // solid 34px-wide permanent green header or gold separator.
        g.drawString(font, switch (panel) {
            case ATLAS -> "ATLAS · 01";
            case MARKERS -> "WAYPOINTS";
            case LAYERS -> "MAP LAYERS";
            case INFO -> "INFORMATION";
            default -> "";
        }, x + 13, y + 9, MUTED);
        g.drawString(font, heading, x + 13, y + 23, CREAM);
        g.drawString(font, "×", x + w - 17, y + 13, GOLD);
        if (!subtitle.isBlank()) {
            g.drawString(font, shortText(subtitle, w - 22), x + 12, y + 42, MUTED);
        }
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String title,
                        boolean active, boolean hovered) {
        int bg = active ? 0xff687b5e : hovered ? 0xff536a52 : 0xff3b5142;
        g.fill(x, y, x + w, y + h, 0xff9d9470);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        g.drawCenteredString(font, shortText(title, w - 8), x + w / 2,
                y + (h - 8) / 2, active ? 0xffffffff : CREAM);
    }

    private String shortText(String value, int maxWidth) {
        if (value == null) return "";
        if (font.width(value) <= maxWidth) return value;
        String result = value;
        while (!result.isEmpty() && font.width(result + "…") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }

    private void renderFloatingPanel(GuiGraphics g, int mx, int my) {
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        switch (panel) {
            case ATLAS -> renderAtlasPanel(g, x, y, w, h, mx, my);
            case MARKERS -> renderMarkersPanel(g, x, y, w, h, mx, my);
            case LAYERS -> renderLayersPanel(g, x, y, w, h, mx, my);
            case INFO -> renderInfoPanel(g, x, y, w, h, mx, my);
            default -> { }
        }
    }

    private AtlasState state() {
        return CartographClient.currentAtlas().getOrDefault(
                CartographMod.ATLAS_STATE.get(), AtlasState.blank());
    }

    private MapNetwork.Page centerPage() {
        return CartographClient.page(Math.floorDiv(toWorldX(screenMidX()), 128),
                Math.floorDiv(toWorldZ(screenMidY()), 128));
    }

    private void meter(GuiGraphics g, String name, int index, int units, int x, int y, int width) {
        g.drawString(font, name, x, y, CREAM);
        String amount = String.format(java.util.Locale.ROOT, "%.1f", units / 10.0);
        g.drawString(font, amount, x + width - font.width(amount), y, GOLD);
        g.fill(x, y + 14, x + width, y + 19, 0xff526458);
        int color = index == 0 ? 0xffdcc28e : index == 1 ? 0xffaf9169 : 0xffb8d0ab;
        int max = index == 2 ? 32 : 128;
        int occupied = (int) (width * Math.min(1.0, (units / 10.0) / max));
        if (occupied > 0) g.fill(x, y + 14, x + occupied, y + 19, color);
    }

    private int consumableY() { return panelY() + (vh() < 280 ? 112 : vh() < 295 ? 133 : tiny() ? 142 : compact() ? 167 : 198); }
    private int compassY() { return panelY() + (vh() < 280 ? 166 : vh() < 295 ? 176 : tiny() ? 198 : compact() ? 236 : 283); }

    private void renderAtlasPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        card(g, x, y, w, h, "地图册 · 资源", "首次测绘消耗材料，更新免费");
        AtlasState item = state();
        MapNetwork.Page page = centerPage();
        int ink = page == null ? item.inkUnits() : page.inkUnits();
        int pigment = page == null ? item.pigmentUnits() : page.pigmentUnits();
        int glow = page == null ? item.glowUnits() : page.glowUnits();
        int step = vh() < 280 ? 19 : vh() < 295 ? 22 : tiny() ? 24 : compact() ? 29 : 36;
        int firstY = vh() < 280 ? 45 : vh() < 295 ? 50 : tiny() ? 53 : 62;
        meter(g, "墨水", 0, ink, x + 13, y + firstY, w - 26);
        meter(g, "颜料", 1, pigment, x + 13, y + firstY + step, w - 26);
        meter(g, "发光墨水", 2, glow, x + 13, y + firstY + 2 * step, w - 26);
        int consumable = consumableY();
        g.drawString(font, "耗材填入口 · 通用槽", x + 13, consumable - 15, GOLD);
        button(g, x + 12, consumable, w - 24, 27, "放入墨囊或染料 ＋",
                false, contains(mx, my, x + 12, consumable, w - 24, 27));
        g.renderItem(new ItemStack(Items.INK_SAC), x + 17, consumable + 5);
        if (!tiny()) g.drawString(font, "红 " + item.redDyes() + "  黄 " + item.yellowDyes()
                        + "  蓝 " + item.blueDyes() + (item.colorEnabled() ? "  ✓ 已上色" : ""),
                x + 13, consumable + 34, MUTED);
        int compass = compassY();
        g.drawString(font, "非耗材填入口", x + 13, compass - 14, GOLD);
        boolean equipped = page == null ? item.compassInstalled() : page.compass();
        button(g, x + 12, compass, w - 24, 24,
                equipped ? "指南针 ✓ 取出" : "指南针 ＋ 装入",
                false, contains(mx, my, x + 12, compass, w - 24, 24));
        g.renderItem(new ItemStack(Items.COMPASS), x + 17, compass + 4);
    }

    private List<AtlasMarker> availableMarkers() {
        List<AtlasMarker> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            for (AtlasMarker m : page.markers()) {
                if (ids.add(m.id())) out.add(m);
            }
        }
        if (!markerFilter.isBlank())
            out.removeIf(m -> !m.name().toLowerCase(java.util.Locale.ROOT)
                    .contains(markerFilter.toLowerCase(java.util.Locale.ROOT)));
        out.sort(Comparator.comparingDouble(m ->
                (m.x() - viewX) * (m.x() - viewX) + (m.z() - viewZ) * (m.z() - viewZ)));
        return out;
    }

    private int markerRows() { return Math.max(1, (panelH() - 138) / 33); }
    private void renderMarkersPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        card(g, x, y, w, h, "我的标点", "已加载地图页的标点 · 点击定位");
        List<AtlasMarker> markers = availableMarkers();
        int capacity = markerRows();
        markerScroll = Math.clamp(markerScroll, 0, Math.max(0, markers.size() - capacity));
        g.drawString(font, "已加载区域 · " + markers.size() + " 个结果",
                x + 13, y + 91, GOLD);
        if (markers.isEmpty()) {
            g.drawString(font, markerFilter.isBlank()
                    ? "在已测绘区域右键添加标点" : "没有匹配的标点", x + 14, y + 132, MUTED);
        } else {
            int start = y + 111;
            for (int i = 0; i < capacity && i + markerScroll < markers.size(); i++) {
                AtlasMarker m = markers.get(i + markerScroll);
                int yy = start + i * 33;
                int bg = contains(mx, my, x + 11, yy, w - 22, 29) ? 0xff51644d : 0xff31483b;
                g.fill(x + 11, yy, x + w - 11, yy + 29, bg);
                int color = COLORS[Math.clamp(m.color(), 0, COLORS.length - 1)];
                g.fill(x + 17, yy + 9, x + 28, yy + 20, 0xff000000 | color);
                g.drawString(font, shortText(m.name() + (m.glowing() ? " ✦" : ""), w - 96),
                        x + 33, yy + 5, CREAM);
                g.drawString(font, "X" + m.x() + "  Z" + m.z(), x + 33, yy + 17, MUTED);
                g.drawString(font, "编辑", x + w - 38, yy + 11, GOLD);
            }
        }
        g.drawString(font, "仅显示已读取的页；滚轮查看列表", x + 13, y + h - 22, MUTED);
    }

    private int layerTop() { return vh() < 295 ? 52 : vh() < 350 ? 59 : 66; }
    private int layerStep() { return vh() < 295 ? 35 : vh() < 350 ? 39 : 47; }
    private int layerRowH() { return vh() < 295 ? 32 : vh() < 350 ? 35 : 39; }

    private void renderLayersPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        card(g, x, y, w, h, "地图图层", "只影响显示，不影响测绘和扣费");
        for (int i = 0; i < 4; i++) {
            int yy = y + layerTop() + i * layerStep(), rh = layerRowH();
            if (yy + rh > y + h) break;
            boolean enabled = layerOn(i);
            g.fill(x + 10, yy, x + w - 10, yy + rh,
                    contains(mx, my, x + 10, yy, w - 20, rh) ? 0xff4a614e : 0xff30463a);
            g.drawString(font, LAYER_NAMES[i], x + 18, yy + 5, CREAM);
            g.drawString(font, shortText(LAYER_HINTS[i], w - 84), x + 18, yy + rh - 13, MUTED);
            g.fill(x + w - 41, yy + 9, x + w - 19, yy + rh - 9,
                    enabled ? 0xffbca16f : 0xff536356);
            g.drawString(font, enabled ? "✓" : "×", x + w - 33, yy + rh / 2 - 4,
                    enabled ? DARK : GOLD);
        }
    }

    private boolean layerOn(int index) {
        return switch (index) {
            case 0 -> drawStructures;
            case 1 -> drawRoads;
            case 2 -> drawRails;
            case 3 -> drawWaypoints;
            default -> false;
        };
    }

    private void toggleLayer(int index) {
        switch (index) {
            case 0 -> drawStructures = !drawStructures;
            case 1 -> drawRoads = !drawRoads;
            case 2 -> drawRails = !drawRails;
            case 3 -> drawWaypoints = !drawWaypoints;
            default -> { return; }
        }
        if (index == 1 || index == 2) CartographClient.showSurfaceFeatures(drawRoads, drawRails);
    }

    private void renderInfoPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        card(g, x, y, w, h, "地图信息", "大地图全屏 · 没有固定边框");
        g.drawString(font, "单页尺寸   128 × 128 方块", x + 13, y + 68, CREAM);
        g.drawString(font, "地图页数   " + state().pages(), x + 13, y + 88, CREAM);
        g.drawString(font, "地图中心   X " + (int) viewX + "  Z " + (int) viewZ,
                x + 13, y + 108, CREAM);
        g.drawString(font, "地图缩放   " + (int) (zoomPixels * 100) + "%", x + 13, y + 128, CREAM);
        g.drawString(font, "拖动地图：左键拖动", x + 13, y + 157, MUTED);
        g.drawString(font, "放大缩小：滚动滚轮", x + 13, y + 174, MUTED);
        g.drawString(font, "添加/编辑标点：右键地图", x + 13, y + 191, MUTED);
        g.drawString(font, "右键地图亦可拓印原版地图", x + 13, y + 208, MUTED);
    }

    /** Compact prototype-style hover badge. No full-width footer/bar. */
    private void renderSurveyBadge(GuiGraphics g, int originalX, int originalY) {
        if (vh() < 140 || vw() < 210) return;
        int viewPageX = Math.floorDiv((int) Math.floor(viewX), 128);
        int viewPageZ = Math.floorDiv((int) Math.floor(viewZ), 128);
        MapNetwork.Page page = centerPage();
        String status;
        if (!CartographClient.coverageKnown()) {
            status = "等待服务器确认地图册与已购买页...";
        } else if (!CartographClient.covered(viewPageX, viewPageZ)
                || (page != null && !page.inRange())) {
            status = "此处未扩充地图页 · 工作台用地图册+空地图增加";
        } else if (page == null) {
            status = "等待区块测绘 · 已排队 " + CartographClient.pendingPages() + " 页";
        } else if (!page.complete()) {
            status = "测绘中 " + page.scanned() + "/64 · 已生成 " +
                    page.generated() + " · 已绘制 " + page.painted();
        } else if (page.generated() == 0) {
            status = "此页尚无可读取的完整生成区块（未生成区域保持空白）";
        } else if (page.painted() == 0) {
            status = !state().inkEnabled()
                    ? "发现 " + page.generated() + " 个已生成区块，请先加入墨囊"
                    : "发现 " + page.generated() + " 个已生成区块，墨水不足无法绘制";
        } else {
            status = "已绘制 " + page.painted() + "/" + page.generated() +
                    " 个真实区块 · 旧区块更新不重复扣费";
        }

        int x = 16, y = vh() - 49;
        int maxWidth = Math.min(300, vw() - 32);
        g.fill(x, y, x + maxWidth, y + 34, 0xd91c3025);
        int worldX = toWorldX(originalX);
        int worldZ = toWorldZ(originalY);
        g.drawString(font, shortText("X " + worldX + "  Z " + worldZ +
                "    " + String.format(java.util.Locale.ROOT, "%.2f", zoomPixels) + "×",
                maxWidth - 18), x + 9, y + 7, GOLD);
        g.drawString(font, shortText(status, maxWidth - 18), x + 9, y + 20, MUTED);
    }

    private void renderContext(GuiGraphics g, int mx, int my) {
        int items = context == Context.MATERIAL ? 5 : 2;
        int w = context == Context.MATERIAL ? 159 : 170;
        int h = items * 27 + 9;
        int x = Math.clamp(contextX, 4, Math.max(4, vw() - w - 4));
        int y = Math.clamp(contextY, 4, Math.max(4, vh() - h - 4));
        g.fill(x, y, x + w, y + h, 0xf91b3025);
        g.fill(x, y, x + w, y + 1, 0xffceb27f);
        for (int i = 0; i < items; i++) {
            String label = switch (context) {
                case MAP -> i == 0 ? "＋ 添加标点" : "▣ 取出原版地图";
                case MARKER -> i == 0 ? "✎ 编辑标点" : "× 删除标点";
                case MATERIAL -> MATERIALS[i];
                default -> "";
            };
            int yy = y + 5 + i * 27;
            button(g, x + 5, yy, w - 10, 23, label,
                    false, contains(mx, my, x + 5, yy, w - 10, 23));
        }
    }

    private int modalW() { return Math.min(320, Math.max(210, vw() - 24)); }
    private int modalH() { return dialog == Dialog.SETTINGS ? Math.min(309, vh() - 22) : Math.min(302, vh() - 22); }
    private int markerOffset(int wide, int medium, int tiny) {
        return modalH() < 252 ? tiny : modalH() < 300 ? medium : wide;
    }
    private int settingsOffset(int wide, int medium, int tiny) {
        return modalH() < 238 ? tiny : modalH() < 307 ? medium : wide;
    }
    private int modalX() { return (vw() - modalW()) / 2; }
    private int modalY() { return (vh() - modalH()) / 2; }

    private void renderDialog(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, vw(), vh(), 0xa80b1711);
        int x = modalX(), y = modalY(), w = modalW(), h = modalH();
        g.fill(x - 4, y + 5, x + w + 4, y + h + 6, 0x98121d16);
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x, y, x + w, y + 37, 0xff374c3d);
        g.drawString(font, dialog == Dialog.SETTINGS ? "模组设置" :
                (selectedMarker == null ? "新增地图标点" : "编辑地图标点"), x + 17, y + 15, CREAM);
        g.drawString(font, "×", x + w - 21, y + 15, GOLD);
        if (dialog == Dialog.MARKER) renderMarkerDialog(g, mx, my);
        else renderSettingsDialog(g, mx, my);
    }

    private int iconBoxW() { return Math.max(25, (modalW() - 38 - 5 * 5) / 6); }
    private void renderMarkerDialog(GuiGraphics g, int mx, int my) {
        int x = modalX(), y = modalY(), w = modalW(), h = modalH();
        g.drawString(font, "X " + pendingX + "  Z " + pendingZ,
                x + 17, y + markerOffset(44, 39, 34), MUTED);
        g.drawString(font, "标点名称", x + 17, y + markerOffset(65, 54, 46), GOLD);
        g.drawString(font, "图标", x + 17, y + markerOffset(103, 95, 83), GOLD);
        int iconY = y + markerOffset(118, 108, 93);
        int iconH = markerOffset(28, 26, 22);
        for (int i = 0; i < 6; i++) {
            int bx = x + 17 + i * (iconBoxW() + 5);
            button(g, bx, iconY, iconBoxW(), iconH, ICONS[i],
                    i == chosenIcon, contains(mx, my, bx, iconY, iconBoxW(), iconH));
        }
        g.drawString(font, "颜色", x + 17, y + markerOffset(156, 139, 121), GOLD);
        if (!canColor())
            g.drawString(font, shortText("需先集齐红黄蓝三原色", w - 82),
                    x + 53, y + markerOffset(156, 139, 121), MUTED);
        int colorY = y + markerOffset(174, 153, 134);
        int swatchH = markerOffset(20, 18, 16);
        for (int i = 0; i < 6; i++) {
            int bx = x + 21 + i * (iconBoxW() + 5);
            g.fill(bx, colorY, bx + iconBoxW() - 7, colorY + swatchH,
                    canColor() ? 0xff000000 | COLORS[i] : 0xff666d64);
            if (chosenColor == i) {
                g.fill(bx, colorY - 3, bx + iconBoxW() - 7, colorY - 1, GOLD);
                g.fill(bx, colorY + swatchH + 1, bx + iconBoxW() - 7,
                        colorY + swatchH + 3, GOLD);
            }
        }
        int glowY = y + markerOffset(209, 182, 158);
        int glowH = markerOffset(26, 24, 22);
        button(g, x + 17, glowY, w - 34, glowH,
                "发光标点  " + (chosenGlow ? "✓ 已开启" : "○ 关闭"),
                chosenGlow, contains(mx, my, x + 17, glowY, w - 34, glowH));
        if (modalH() >= 270)
            g.drawString(font, "首次发光扣 1 × 倍率，编辑不重扣",
                    x + 17, y + markerOffset(244, 216, 0), MUTED);
        int btnY = y + h - 36, bw = (w - 43) / 2;
        button(g, x + 17, btnY, bw, 25, "取消", false,
                contains(mx, my, x + 17, btnY, bw, 25));
        button(g, x + 26 + bw, btnY, bw, 25, "保存标点", true,
                contains(mx, my, x + 26 + bw, btnY, bw, 25));
    }

    private void renderSettingsDialog(GuiGraphics g, int mx, int my) {
        int x = modalX(), y = modalY(), w = modalW(), h = modalH();
        boolean editable = CartographClient.settings().editable();
        g.drawString(font, editable ? "倍率 0～10×；0 = 不扣材料" : "服务器设置 · 需要管理员权限",
                x + 17, y + settingsOffset(44, 39, 34), MUTED);
        g.drawString(font, "每次填充材料增加的资源量",
                x + 17, y + settingsOffset(192, 151, 131), GOLD);
        int boxW = (w - 46) / 3;
        for (int i = 0; i < 3; i++) {
            g.drawString(font, RATE_NAMES[i], x + 17 + i * (boxW + 6),
                    y + settingsOffset(207, 164, 143), MUTED);
        }
        if (h >= 260)
            g.drawString(font, "倍率为零仍需首次投入相应材料", x + 17,
                    y + settingsOffset(254, 207, 0), MUTED);
        int btnY = y + h - 35, bw = (w - 43) / 2;
        button(g, x + 17, btnY, bw, 25, "取消", false,
                contains(mx, my, x + 17, btnY, bw, 25));
        button(g, x + 26 + bw, btnY, bw, 25, editable ? "保存设置" : "只读",
                editable, contains(mx, my, x + 26 + bw, btnY, bw, 25));
    }

    private final class RateSlider extends AbstractSliderButton {
        private final int kind;
        RateSlider(int x, int y, int w, int h, int kind) {
            super(x, y, w, h, Component.empty(), rates[kind] / 100.0);
            this.kind = kind;
            updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.literal(RATE_NAMES[kind] + "     " +
                    (Math.round(value * 100) / 10.0) + "×"));
        }
        @Override protected void applyValue() {
            rates[kind] = Math.clamp((int) Math.round(value * 100), 0, 100);
            updateMessage();
        }
    }

    private void setupSettingsWidgets() {
        clearWidgets();
        int x = modalX(), y = modalY(), w = modalW();
        MapNetwork.CostSettings settings = CartographClient.settings();
        rates = new int[]{settings.ink(), settings.pigment(), settings.glow()};
        int sliderStart = settingsOffset(64, 52, 44);
        int sliderStep = settingsOffset(37, 31, 29);
        for (int i = 0; i < 3; i++) {
            RateSlider slider = new RateSlider(x + 17, y + sliderStart + sliderStep * i,
                    w - 34, 21, i);
            slider.active = settings.editable();
            addRenderableWidget(slider);
        }
        int[] values = {settings.inkRefill(), settings.pigmentRefill(), settings.glowRefill()};
        int boxW = (w - 46) / 3;
        for (int i = 0; i < 3; i++) {
            EditBox input = new EditBox(font, x + 17 + i * (boxW + 6),
                    y + settingsOffset(222, 178, 159), boxW, 21, Component.literal("补充量"));
            input.setFilter(value -> value.matches("[0-9]{0,5}"));
            input.setMaxLength(5);
            input.setValue(String.valueOf(values[i]));
            input.setEditable(settings.editable());
            refillInput[i] = addRenderableWidget(input);
        }
    }

    private int readRefill(EditBox field, int fallback) {
        try {
            return Math.clamp(Integer.parseInt(field.getValue()), 1, 10000);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void setupMarkerWidget() {
        clearWidgets();
        int x = modalX(), y = modalY();
        markerName = new EditBox(font, x + 17, y + markerOffset(79, 69, 60),
                modalW() - 34, 19,
                Component.literal("标点名称"));
        markerName.setMaxLength(24);
        markerName.setValue(selectedMarker == null ? "标点" : selectedMarker.name());
        markerName = addRenderableWidget(markerName);
        setInitialFocus(markerName);
    }

    private void openMarkerDialog(AtlasMarker existing, int x, int z) {
        hidePanel();
        context = Context.NONE;
        dialog = Dialog.MARKER;
        selectedMarker = existing;
        pendingX = existing == null ? x : existing.x();
        pendingZ = existing == null ? z : existing.z();
        chosenIcon = existing == null ? 0 : existing.icon();
        chosenColor = existing == null ? 0 : existing.color();
        chosenGlow = existing != null && existing.glowing();
        setupMarkerWidget();
    }

    private void openSettings() {
        hidePanel();
        context = Context.NONE;
        dialog = Dialog.SETTINGS;
        setupSettingsWidgets();
    }

    private boolean canColor() { return state().colorEnabled(); }
    private void closeDialog() {
        clearWidgets();
        markerName = null;
        for (int i = 0; i < refillInput.length; i++) refillInput[i] = null;
        selectedMarker = null;
        dialog = Dialog.NONE;
    }

    private void saveMarker() {
        if (markerName == null) return;
        if (selectedMarker == null && !canMark(pendingX, pendingZ)) {
            toast("仅能在已测绘位置添加标点");
            return;
        }
        CartographClient.sendMarker(new MapNetwork.EditMarker(
                CartographClient.atlasId(),
                selectedMarker == null ? "" : selectedMarker.id(),
                pendingX, pendingZ, markerName.getValue(),
                chosenIcon, canColor() ? chosenColor : 0, chosenGlow, false));
        actionRefreshTicks = 50;
        closeDialog();
        toast("已提交标点保存请求");
    }

    private void saveSettings() {
        MapNetwork.CostSettings server = CartographClient.settings();
        if (!server.editable()) {
            toast("只有管理员可以修改服务端设置");
            return;
        }
        CartographClient.sendSettings(rates[0], rates[1], rates[2],
                readRefill(refillInput[0], server.inkRefill()),
                readRefill(refillInput[1], server.pigmentRefill()),
                readRefill(refillInput[2], server.glowRefill()));
        closeDialog();
        toast("设置已提交至服务器");
    }

    private void toast(String message) {
        notice = message;
        noticeUntil = frameTicks + 90;
    }

    private AtlasMarker markerNear(double mx, double my) {
        if (!drawWaypoints) return null;
        AtlasMarker nearest = null;
        double best = 100;
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            for (AtlasMarker marker : page.markers()) {
                double dx = mx - toScreenX(marker.x());
                double dy = my - toScreenZ(marker.z());
                double sq = dx * dx + dy * dy;
                if (sq < best) { nearest = marker; best = sq; }
            }
        }
        return nearest;
    }

    private boolean canMark(int x, int z) {
        MapNetwork.Page page = CartographClient.page(Math.floorDiv(x, 128), Math.floorDiv(z, 128));
        if (page == null || !page.inRange()) return false;
        int px = Math.floorMod(x, 128), pz = Math.floorMod(z, 128);
        return Byte.toUnsignedInt(page.pixels()[px + pz * 128]) > 0;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double guiX = ux(mx), guiY = uy(my);
        if (dialog != Dialog.NONE) return dialogClicked(guiX, guiY, button);
        if (context != Context.NONE) {
            if (handleContextClick(guiX, guiY, button)) return true;
            context = Context.NONE;
        }
        if (handleToolbarClick(guiX, guiY, button)) return true;
        if (panel != Panel.NONE) {
            int x = panelX(), y = panelY(), w = panelW(), h = panelH();
            if (contains(guiX, guiY, x, y, w, h)) {
                if (panel == Panel.MARKERS && super.mouseClicked(guiX, guiY, button)) return true;
                return panelClicked(guiX, guiY, button);
            }
            hidePanel();
        }
        if (button == 1) {
            pendingX = toWorldX(mx);
            pendingZ = toWorldZ(my);
            selectedMarker = markerNear(mx, my);
            if (selectedMarker != null) {
                pendingX = selectedMarker.x();
                pendingZ = selectedMarker.z();
            }
            context = selectedMarker == null ? Context.MAP : Context.MARKER;
            contextX = (int) guiX;
            contextY = (int) guiY;
            return true;
        }
        if (button == 0) {
            draggingMap = true;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean handleToolbarClick(double mx, double my, int button) {
        int x = toolbarX(), y = toolY(), w = toolW();
        if (contains(mx, my, x - 6, y - 6, toolbarWidth() + 6, 51)) {
            if (button == 0) {
                for (int i = 0; i < TOOLS.length; i++) {
                    if (!contains(mx, my, x + i * (w + 4), y, w, 37)) continue;
                    context = Context.NONE;
                    switch (i) {
                        case 0 -> togglePanel(Panel.ATLAS);
                        case 1 -> { markerScroll = 0; togglePanel(Panel.MARKERS); }
                        case 2 -> togglePanel(Panel.LAYERS);
                        case 3 -> openSettings();
                        case 4 -> recenter();
                        case 5 -> togglePanel(Panel.INFO);
                        case 6 -> minecraft.setScreen(null);
                        default -> { }
                    }
                    break;
                }
            }
            return true;
        }
        return false;
    }

    private void setupMarkerSearch() {
        clearWidgets();
        markerSearch = new EditBox(font, panelX() + 13, panelY() + 61,
                panelW() - 26, 20, Component.literal("搜索标点"));
        markerSearch.setMaxLength(24);
        markerSearch.setValue(markerFilter);
        markerSearch.setResponder(value -> {
            markerFilter = value;
            markerScroll = 0;
        });
        markerSearch = addRenderableWidget(markerSearch);
    }

    private void hidePanel() {
        if (markerSearch != null) {
            clearWidgets();
            markerSearch = null;
        }
        panel = Panel.NONE;
    }

    private void togglePanel(Panel next) {
        if (panel == next) hidePanel();
        else {
            hidePanel();
            panel = next;
            if (next == Panel.MARKERS) setupMarkerSearch();
        }
    }

    private void recenter() {
        if (minecraft != null && minecraft.player != null) {
            viewX = minecraft.player.getBlockX();
            viewZ = minecraft.player.getBlockZ();
            reloadVisible(false);
        }
        hidePanel();
    }

    private boolean panelClicked(double mx, double my, int button) {
        if (button != 0) return true;
        int x = panelX(), y = panelY(), w = panelW();
        if (contains(mx, my, x + w - 29, y, 29, 34)) {
            hidePanel();
            return true;
        }
        if (panel == Panel.ATLAS) {
            if (contains(mx, my, x + 12, consumableY(), w - 24, 27)) {
                context = Context.MATERIAL;
                contextX = Math.max(8, x - 164);
                contextY = consumableY();
                return true;
            }
            if (contains(mx, my, x + 12, compassY(), w - 24, 24)) {
                CartographClient.sendRefill(5);
                actionRefreshTicks = 45;
                return true;
            }
        } else if (panel == Panel.LAYERS) {
            for (int i = 0; i < 4; i++) {
                if (contains(mx, my, x + 10, y + layerTop() + i * layerStep(),
                        w - 20, layerRowH())) {
                    toggleLayer(i);
                    return true;
                }
            }
        } else if (panel == Panel.MARKERS) {
            List<AtlasMarker> markers = availableMarkers();
            for (int i = 0; i < markerRows() && i + markerScroll < markers.size(); i++) {
                int yy = y + 111 + i * 33;
                if (!contains(mx, my, x + 11, yy, w - 22, 29)) continue;
                AtlasMarker marker = markers.get(i + markerScroll);
                if (mx > x + w - 55) {
                    openMarkerDialog(marker, marker.x(), marker.z());
                } else {
                    viewX = marker.x();
                    viewZ = marker.z();
                    reloadVisible(false);
                }
                return true;
            }
        }
        return true;
    }

    private boolean handleContextClick(double mx, double my, int button) {
        int items = context == Context.MATERIAL ? 5 : 2;
        int w = context == Context.MATERIAL ? 159 : 170;
        int h = items * 27 + 9;
        int x = Math.clamp(contextX, 4, Math.max(4, vw() - w - 4));
        int y = Math.clamp(contextY, 4, Math.max(4, vh() - h - 4));
        if (!contains(mx, my, x, y, w, h)) return false;
        if (button == 0) {
            for (int i = 0; i < items; i++) {
                if (!contains(mx, my, x + 5, y + 5 + i * 27, w - 10, 23)) continue;
                switch (context) {
                    case MATERIAL -> {
                        CartographClient.sendRefill(i);
                        actionRefreshTicks = 45;
                    }
                    case MAP -> {
                        if (!canMark(pendingX, pendingZ)) {
                            toast("区域尚未测绘，无法操作");
                            break;
                        }
                        if (i == 0) openMarkerDialog(null, pendingX, pendingZ);
                        else {
                            CartographClient.exportAt(pendingX, pendingZ);
                            toast("已请求拓印，需要背包有空地图");
                        }
                    }
                    case MARKER -> {
                        if (selectedMarker == null) break;
                        if (i == 0) openMarkerDialog(selectedMarker, selectedMarker.x(), selectedMarker.z());
                        else {
                            CartographClient.sendMarker(new MapNetwork.EditMarker(
                                    CartographClient.atlasId(), selectedMarker.id(),
                                    selectedMarker.x(), selectedMarker.z(),
                                    selectedMarker.name(), selectedMarker.icon(),
                                    selectedMarker.color(), selectedMarker.glowing(), true));
                            actionRefreshTicks = 45;
                            selectedMarker = null;
                        }
                    }
                    default -> { }
                }
                context = Context.NONE;
                break;
            }
        }
        return true;
    }

    private boolean dialogClicked(double mx, double my, int button) {
        if (button != 0) return true;
        if (super.mouseClicked(mx, my, button)) return true;
        int x = modalX(), y = modalY(), w = modalW(), h = modalH();
        if (contains(mx, my, x + w - 40, y, 40, 37)) {
            closeDialog();
            return true;
        }
        int bw = (w - 43) / 2, by = y + h - (dialog == Dialog.SETTINGS ? 35 : 36);
        if (contains(mx, my, x + 17, by, bw, 25)) {
            closeDialog();
            return true;
        }
        if (contains(mx, my, x + 26 + bw, by, bw, 25)) {
            if (dialog == Dialog.MARKER) saveMarker();
            else saveSettings();
            return true;
        }
        if (dialog == Dialog.MARKER) {
            int iconY = y + markerOffset(118, 108, 93);
            int iconH = markerOffset(28, 26, 22);
            int colorY = y + markerOffset(174, 153, 134);
            int colorH = markerOffset(20, 18, 16);
            for (int i = 0; i < 6; i++) {
                int bx = x + 17 + i * (iconBoxW() + 5);
                if (contains(mx, my, bx, iconY, iconBoxW(), iconH)) {
                    chosenIcon = i;
                    return true;
                }
                if (contains(mx, my, bx + 4, colorY, iconBoxW() - 7, colorH)) {
                    if (canColor()) chosenColor = i;
                    else toast("需要先投入红、黄、蓝三原色");
                    return true;
                }
            }
            int glowY = y + markerOffset(209, 182, 158);
            int glowH = markerOffset(26, 24, 22);
            if (contains(mx, my, x + 17, glowY, w - 34, glowH)) {
                if (!chosenGlow && !state().glowEnabled()) toast("需要先投入发光墨囊");
                else chosenGlow = !chosenGlow;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dialog != Dialog.NONE)
            return super.mouseDragged(ux(mx), uy(my), button, ux(dx), uy(dy));
        if (button == 0 && draggingMap) {
            viewX = Math.clamp(viewX - dx / zoom, -30_000_000, 30_000_000);
            viewZ = Math.clamp(viewZ - dy / zoom, -30_000_000, 30_000_000);
            reloadVisible(false);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) draggingMap = false;
        return super.mouseReleased(ux(mx), uy(my), button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double deltaX, double deltaY) {
        double guiX = ux(mx), guiY = uy(my);
        if (dialog != Dialog.NONE)
            return super.mouseScrolled(guiX, guiY, deltaX, deltaY);
        if (context != Context.NONE) return true;
        if (panel == Panel.MARKERS && contains(guiX, guiY, panelX(), panelY(), panelW(), panelH())) {
            int max = Math.max(0, availableMarkers().size() - markerRows());
            markerScroll = Math.clamp(markerScroll - (int) Math.signum(deltaY), 0, max);
            return true;
        }
        if (panel != Panel.NONE && contains(guiX, guiY, panelX(), panelY(), panelW(), panelH())) return true;
        if (contains(guiX, guiY, toolbarX() - 6, toolY() - 6, toolbarWidth() + 6, 51)) return true;
        double originalX = viewX + (mx - screenMidX()) / zoom;
        double originalZ = viewZ + (my - screenMidY()) / zoom;
        zoomPixels = Math.clamp(zoomPixels * (deltaY > 0 ? 1.25 : 0.8), .4, 10.0);
        zoom = zoomPixels / Math.max(1, minecraft.getWindow().getGuiScale());
        viewX = Math.clamp(originalX - (mx - screenMidX()) / zoom, -30_000_000, 30_000_000);
        viewZ = Math.clamp(originalZ - (my - screenMidY()) / zoom, -30_000_000, 30_000_000);
        reloadVisible(false);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (dialog != Dialog.NONE) { closeDialog(); return true; }
            if (context != Context.NONE) { context = Context.NONE; return true; }
            if (panel != Panel.NONE) { hidePanel(); return true; }
            return super.keyPressed(key, scan, mods);
        }
        if (dialog != Dialog.NONE) return super.keyPressed(key, scan, mods);
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> viewX -= 128;
            case GLFW.GLFW_KEY_RIGHT -> viewX += 128;
            case GLFW.GLFW_KEY_UP -> viewZ -= 128;
            case GLFW.GLFW_KEY_DOWN -> viewZ += 128;
            default -> { return super.keyPressed(key, scan, mods); }
        }
        viewX = Math.clamp(viewX, -30_000_000, 30_000_000);
        viewZ = Math.clamp(viewZ, -30_000_000, 30_000_000);
        reloadVisible(false);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
