package com.wanstu.cartograph;

import com.wanstu.cartograph.ClientMapSettings.Style;
import com.wanstu.cartograph.NamedRegion.Point;
import com.wanstu.cartograph.core.TerrainRelief;
import com.wanstu.cartograph.core.MapSheetCoverage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

import static com.wanstu.cartograph.AtlasChrome.*;
import static com.wanstu.cartograph.ClientMapSettings.*;

/** Native screen built against preview/live's approved full atlas and settings flows. */
public final class AtlasScreen extends Screen {
    private enum Panel { NONE, LAYERS, MARKERS, REGIONS, ATLAS, INFO }
    private enum Dialog { NONE, SETTINGS, MATERIALS, MARKER, REGION, CONFIRM }
    private enum SettingsTab { MAP, MINI, COST, CACHE }
    private enum PixelView { PREVIOUS, ENHANCED, SPLIT }
    private enum Menu { NONE, MAP, VERTEX }

    private static final String[] TOOLS = {"图层", "标点", "区域", "地图册", "设置", "信息", "返回"};
    private static final String[] LAYERS = {"道路", "铁路", "地表结构", "地图标点", "区域名称", "区域边界", "等高线"};
    private static final String[] HUD_FLAGS = {"启用悬浮小地图", "显示东南西北", "锁定北方", "显示坐标", "显示区域名称", "显示地图图标"};
    private static final String[] RESOURCES = {"测绘墨水", "彩色颜料", "发光墨水", "地图纸值"};
    private static final String[] PERMANENT = {"永久测绘墨水", "永久彩色", "永久发光墨水", "永久地图纸"};
    private static final String[] ICON_NAMES = {"住处", "旗帜", "矿区", "地标", "港口", "其他", "城镇", "农田",
            "营地", "危险", "传送门", "宝藏", "矿洞", "桥梁", "山峰", "交易点"};
    private static final String[] GLYPHS = {"⌂", "⚑", "◆", "✦", "⚓", "●", "▣", "✿",
            "▲", "!", "◈", "★", "▼", "═", "△", "$"};
    private static final int[] COLORS = {0xf7f2df, 0xe99357, 0xeeca53, 0x70b67b, 0x72a7d1, 0xcf89c0};
    private static final int[] REGION_COLORS = {0x629ab4, 0xab855e, 0x6d9a70, 0xc3af73, 0x81adb1, 0xa0ae7d, 0xb99fc7};
    private static final Item[] MATERIALS = {Items.INK_SAC, Items.GLOW_INK_SAC, Items.RED_DYE, Items.YELLOW_DYE, Items.BLUE_DYE, Items.COMPASS, Items.MAP};
    private static final String[] MATERIAL_NAMES = {"墨囊", "发光墨囊", "红色染料", "黄色染料", "蓝色染料", "指南针", "空地图"};
    private static final int WORLD_LIMIT = 30_000_000;
    // Unmapped tiles and unrevealed terrain have a single paper color.
    // The previous grey backdrop made 128x128 missing tiles look like clouds.
    private static final int BACKGROUND = 0xff000000 | PixelPageTexture.PAPER;

    private final ClientMapSettings cfg = ClientMapSettings.get();
    private final List<Hit> hits = new ArrayList<>();
    private Panel panel = Panel.NONE;
    private Dialog dialog = Dialog.NONE;
    private SettingsTab settingsTab = SettingsTab.MAP;
    private PixelView pixelView = PixelView.ENHANCED;
    private Menu menu = Menu.NONE;
    private double viewX, viewZ, zoomPixels = 2.35, zoom, split = .50;
    private double pressX, pressY, dragX, dragZ;
    private float ui = 1;
    private boolean initialized, manuallyMoved, panning, moved, movingSplit, suppressSelection;
    private int ticks, scroll, settingsScroll, mouseX, mouseY, contextX, contextY, contextWorldX, contextWorldZ;
    private String message = "", status = "";
    private int messageUntil;
    private boolean badMessage;
    private String materialNotice = "";
    private boolean materialNoticeError;
    private boolean annotationPending, materialPending;
    private int requestStartedAtTick;
    private Selection selection;
    private EditBox name, coordinateX, coordinateZ, hexColor, note;
    private AtlasMarker editingMarker;
    private NamedRegion editingRegion;
    private int markerIcon, regionType;
    private boolean markerGlow;
    private List<Point> draft;
    private boolean adjustVertices;
    private int grabbedVertex = -1, menuVertex = -1, clickedEdge = -1;
    private long edgeClickNanos;
    private final int[] inputs = new int[4], costs = new int[4];
    private int permanentFlags;
    private int newSheetSideBlocks = MapSheetCoverage.DEFAULT_BLOCKS;
    private boolean ratesDirty, performanceDirty;
    private int preferredWorkers = 4, preferredBudgetMs = 4;
    private Slider activeSlider;
    private String confirmation = "";
    private Runnable confirmed;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return inside(mx, my, x, y, w, h); }
    }
    private record Slider(int x, int width, DoubleConsumer set) {
        void update(double mx) { set.accept(Math.clamp((mx - x) / width, 0, 1)); }
    }
    private record Selection(int x, int z, AtlasMarker marker, NamedRegion region, SurfaceStructure structure) {}

    public AtlasScreen() { super(Component.translatable("screen.cartograph.atlas")); }

    static String markerGlyph(int icon) { return GLYPHS[Math.clamp(icon, 0, GLYPHS.length - 1)]; }
    private int w() { return (int) Math.ceil(width / ui); }
    private int h() { return (int) Math.ceil(height / ui); }
    private double ux(double x) { return x / ui; }

    /** The map toolbar and status bar should be readable even on 720p screens.
     * A large, multi-control modal has different constraints: it is allowed
     * to use a smaller integer pixel scale to keep ALL controls on screen.
     * Both modes use integer physical pixels, avoiding fractional-font blur.
     */
    private void updateUiScale() {
        double minecraftScale = minecraft.getWindow().getGuiScale();
        int physicalW = (int) Math.round(width * minecraftScale);
        int physicalH = (int) Math.round(height * minecraftScale);
        int logicalMinWidth = dialog == Dialog.NONE ? 540 : 520;
        int logicalMinHeight = dialog == Dialog.NONE ? 300
                : dialog == Dialog.SETTINGS ? 306 : modalH() + 36;
        int physicalTextScale = Math.clamp(Math.min(
                physicalW / logicalMinWidth, physicalH / logicalMinHeight), 1, 3);
        ui = (float) (physicalTextScale / minecraftScale);
    }

    @Override protected void init() {
        updateUiScale();
        zoom = zoomPixels / minecraft.getWindow().getGuiScale();
        if (!initialized) {
            initialized = true;
            viewX = minecraft.player.getX(); viewZ = minecraft.player.getZ();
            if (CartographClient.coverage() != null) coverageReady();
            else reload(false, false);
        } else {
            resizeFields();
            reload(false, false);
        }
    }

    public void coverageChanged() { reload(false, false); }

    public void coverageReady() {
        MapNetwork.Page c = CartographClient.coverage();
        if (c == null) return;
        if (!manuallyMoved && c.anchorDimension().equals(CartographClient.dimension())) {
            if (c.totalPages() == 1) {
                zoomPixels = Math.clamp(Math.min(width, height) * minecraft.getWindow().getGuiScale()
                        * .66 / c.sheetSideBlocks(), .20, 6.0);
                zoom = zoomPixels / minecraft.getWindow().getGuiScale();
                goAnchor();
            } else if (!CartographClient.covered(pageX(viewX), pageX(viewZ))) goAnchor();
        }
        CartographClient.requestAnnotations();
        reload(false, false);
    }

    private void goAnchor() {
        MapNetwork.Page c = CartographClient.coverage();
        if (c == null || !c.anchorDimension().equals(CartographClient.dimension())) return;
        focus(MapSheetCoverage.centerBlock(c.anchorX(), c.sheetSideBlocks()),
                MapSheetCoverage.centerBlock(c.anchorZ(), c.sheetSideBlocks()));
        status = "已定位到地图册起始页";
    }

    private void recenter() {
        if (CartographClient.covered(pageX(minecraft.player.getX()), pageX(minecraft.player.getZ()))) {
            focus(minecraft.player.getX(), minecraft.player.getZ()); status = "已回到玩家位置";
        } else goAnchor();
    }

    private void focus(double x, double z) {
        viewX = x; viewZ = z;
        CartographClient.prioritizeViewport();
        reload(false, false);
    }
    private static int pageX(double world) { return Math.floorDiv((int) Math.floor(world), 128); }
    private int worldX(double x) { return world(viewX + (x - width * .5) / zoom); }
    private int worldZ(double y) { return world(viewZ + (y - height * .5) / zoom); }
    private static int world(double p) { return (int) Math.floor(Math.clamp(p, -WORLD_LIMIT, WORLD_LIMIT)); }
    private double screenX(double x) { return width * .5 + (x - viewX) * zoom; }
    private double screenZ(double z) { return height * .5 + (z - viewZ) * zoom; }
    private MapNetwork.Page pageAt(int x, int z) { return CartographClient.page(pageX(x), pageX(z)); }
    private int leftPage() { return pageX(worldX(0)); }
    private int rightPage() { return pageX(worldX(width)); }
    private int topPage() { return pageX(worldZ(0)); }
    private int bottomPage() { return pageX(worldZ(height)); }

    private void reload(boolean force, boolean cachedOnly) {
        int cx = pageX(viewX), cz = pageX(viewZ);
        if (CartographClient.coverage() == null) {
            CartographClient.queue(cx, cz, force, cachedOnly);
            return;
        }
        int count = 0, x0 = leftPage(), x1 = rightPage(), z0 = topPage(), z1 = bottomPage();
        int maxRing = Math.min(160, Math.max(
                Math.max(Math.abs(cx - x0), Math.abs(x1 - cx)),
                Math.max(Math.abs(cz - z0), Math.abs(z1 - cz))));
        for (int ring = 0; ring <= maxRing && count < 64; ring++)
            for (int z = Math.max(z0, cz - ring); z <= Math.min(z1, cz + ring); z++)
                for (int x = Math.max(x0, cx - ring); x <= Math.min(x1, cx + ring); x++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != ring || !CartographClient.covered(x, z)) continue;
                    if (CartographClient.queue(x, z, force, cachedOnly) && ++count >= 64) return;
                }
    }

    @Override public void tick() {
        // Discovery is NOT a 30-second "cooldown": when the viewport has
        // missing tiles and the queue empties, refill it within half a second.
        ticks++;
        if (materialPending && ticks - requestStartedAtTick >= 200) {
            materialPending = false;
            materialNoticeError = true;
            materialNotice = "服务端 10 秒未响应 · 本次操作尚未确认";
            toast(materialNotice, true);
        }
        if (annotationPending && ticks - requestStartedAtTick >= 200) {
            annotationPending = false;
            toast("保存请求超时，未确认成功；内容仍在，可以重试", true);
        }
        if (ticks % 10 == 0 && CartographClient.queuedCount() < 48)
            reload(false, false);
    }

    public void feedback(boolean ok, String text, boolean surveyChanged) {
        toast(text, !ok);
        if (dialog == Dialog.MATERIALS) {
            materialPending = false;
            materialNotice = text;
            materialNoticeError = !ok;
        }
        if (annotationPending && (dialog == Dialog.MARKER || dialog == Dialog.REGION)) {
            annotationPending = false;
            if (ok && (text.contains("标点") || text.contains("区域"))) {
                // Keep editing fields on server rejection: never discard user
                // input just because a request was sent, not acknowledged.
                closeDialog();
            }
        }
        if (!ok) return;
        // Paper refill, naming and compass changes do not invalidate map imagery.
        // Just reload metadata at the central tile. Survey/color changes require
        // refreshing visible tiles, but keep their old GPU textures until replaced.
        if (surveyChanged) reload(true, false);
        else {
            CartographClient.queue(pageX(viewX), pageX(viewZ), true, false);
            reload(false, false);
        }
    }

    private void toast(String value, boolean error) {
        message = value; messageUntil = ticks + 140; badMessage = error;
        if (!error) status = value;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        float oldScale = ui;
        updateUiScale();
        if (Math.abs(oldScale - ui) > 0.001f && dialog != Dialog.NONE)
            resizeFields();
        mouseX = (int) ux(mx); mouseY = (int) ux(my);
        renderMap(g, mx, my);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        try {
            if (dialog == Dialog.NONE) {
                statusBox(g, mx, my);
                toolbar(g);
                // The old/new/split comparison bar was a developer preview control,
                // absent from the approved atlas prototype; do not render it in game.
                if (panel != Panel.NONE) renderPanel(g);
                if (selection != null) renderSelection(g);
                if (draft != null) drawGuide(g);
                if (menu != Menu.NONE) renderMenu(g);
            } else {
                // Drain deferred map draws before a modal gets a new Z layer.
                g.flush();
                g.pose().pushPose();
                g.pose().translate(0, 0, 300);
                renderDialog(g);
                // Minecraft EditBox widgets belong to the dialog too. Rendering
                // them after popping the elevated pose puts them UNDER its solid
                // background, making names/notes/hex colors invisible.
                super.render(g, mouseX, mouseY, partial);
                g.flush();
                g.pose().popPose();
            }
            if (!message.isEmpty() && ticks <= messageUntil) {
                int toastWidth = Math.min(w() - 22, font.width(message) + 26);
                int toastY = dialog == Dialog.NONE ? h() - 43
                        : Math.max(6, modalY() - 32);
                // Draw above the modal (not behind it), never overlapping its
                // material cells or confirmation button.
                g.flush();
                g.pose().pushPose();
                g.pose().translate(0, 0, 450);
                rounded(g, (w() - toastWidth) / 2, toastY, toastWidth, 24, 7,
                        badMessage ? 0xff6b4335 : PANEL);
                String displayed = shorten(message, toastWidth - 12);
                g.drawString(font, displayed,
                        (w() - font.width(displayed)) / 2, toastY + 8, CREAM, false);
                g.flush();
                g.pose().popPose();
            }
            // Outside a modal there are no text widgets; render only once.
            g.flush();
        } finally { g.pose().popPose(); }
    }

    private void renderMap(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, height, BACKGROUND);
        if (cfg.style == Style.PIXEL && pixelView == PixelView.SPLIT) {
            int divider = (int) (split * width);
            mapTiles(g, Style.ORIGINAL, 0, divider);
            mapTiles(g, Style.PIXEL, divider, width);
        } else mapTiles(g, cfg.style == Style.PIXEL && pixelView == PixelView.PREVIOUS ? Style.ORIGINAL : cfg.style, 0, width);
        // Under a modal, glyphs and player labels must not render on top of
        // the dimmer: skip the map annotation pass entirely.
        if (dialog == Dialog.NONE) drawMapLabels(g);
        if (dialog == Dialog.NONE && selection != null) {
            int sx = (int) screenX(selection.x()), sy = (int) screenZ(selection.z());
            g.renderOutline(sx - 5, sy - 5, 11, 11, GOLD);
            g.fill(sx - 1, sy - 1, sx + 2, sy + 2, CREAM);
        }
        if (dialog == Dialog.NONE && draft != null) {
            drawBoundary(g, draft, true, 0xffedd095);
            for (Point point : draft)
                rounded(g, (int) screenX(point.x()) - 3, (int) screenZ(point.z()) - 3, 7, 7, 3, GOLD);
            if (!adjustVertices && !draft.isEmpty()) {
                Point last = draft.getLast();
                line(g, screenX(last.x()), screenZ(last.z()), mx, my, 0xaaffe7b6, 1);
            }
        }
    }

    private void mapTiles(GuiGraphics g, Style style, int start, int end) {
        if (start >= end) return;
        g.enableScissor(start, 0, end, height);
        int x0 = leftPage(), x1 = rightPage(), z0 = topPage(), z1 = bottomPage();
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            int x = page.pageX(), z = page.pageZ();
            if (x < x0 || x > x1 || z < z0 || z > z1) continue;
            ResourceLocation texture = CartographClient.texture(x, z);
            g.pose().pushPose();
            g.pose().translate(screenX(x * 128.0), screenZ(z * 128.0), 0);
            g.pose().scale((float) zoom, (float) zoom, 1);
            if (texture == null) g.fill(0, 0, 128, 128, 0xff000000 | PixelPageTexture.PAPER);
            else PixelPageTexture.blit(g, texture, style, zoomPixels);
            g.pose().popPose();
        }
        g.disableScissor();
    }

    private void drawMapLabels(GuiGraphics g) {
        g.enableScissor(0, 0, width, height);
        for (NamedRegion region : CartographClient.regions()) {
            Point pos = region.labelPosition();
            boolean active = selection != null && selection.region() == region;
            if (cfg.layer(BOUNDARIES) || active)
                drawBoundary(g, region.boundary(), true, active ? GOLD : 0x96000000 | region.color());
            // Far-away zooms must not be dominated by full-size region text.
            if (zoomPixels >= 4.5 && cfg.layer(REGIONS)
                    && CartographClient.surveyed(pos.x(), pos.z())) {
                float size = (float) Math.clamp(zoomPixels / 8.0, .45, 1);
                g.pose().pushPose();
                g.pose().translate(screenX(pos.x()), screenZ(pos.z()), 0);
                g.pose().scale(size, size, 1);
                g.drawCenteredString(font, region.name(), 0, 0, CREAM);
                g.pose().popPose();
            }
        }
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            if (cfg.layer(WAYPOINTS)) for (AtlasMarker m : page.markers())
                if (CartographClient.surveyed(m.x(), m.z()))
                    mapIcon(g, m.x(), m.z(), markerGlyph(m.icon()), m.name(), m.color(), m.glowing());
            if (cfg.layer(STRUCTURES)) for (SurfaceStructure p : page.structures())
                mapIcon(g, p.x(), p.z(), "⌂", structureName(p.type()), 0x577060, false);
        }
        if (CartographClient.state().compassInstalled()
                && CartographClient.surveyed(minecraft.player.getBlockX(), minecraft.player.getBlockZ())) {
            double x = screenX(minecraft.player.getX()), z = screenZ(minecraft.player.getZ());
            double iconScale = Math.clamp(zoomPixels / 7.0, .36, 1.0);
            arrow(g, x, z, Math.toRadians(minecraft.player.getYRot() + 180),
                    .9 * iconScale);
            if (zoomPixels >= 5.5) {
                g.pose().pushPose();
                g.pose().translate(x, z + 7 * iconScale, 0);
                g.pose().scale((float) (ui * iconScale), (float) (ui * iconScale), 1);
                int half = font.width("玩家") / 2 + 4;
                rounded(g, -half, 0, half * 2 + 1, 11, 3, PANEL);
                g.drawCenteredString(font, "玩家", 0, 2, CREAM);
                g.pose().popPose();
            }
        }
        g.disableScissor();
    }

    private void mapIcon(GuiGraphics g, int x, int z, String glyph, String label, int color, boolean glow) {
        int sx = (int) screenX(x), sy = (int) screenZ(z);
        if (!inside(sx, sy, -40, -40, width + 80, height + 80)) return;
        // Icon size follows the ACTUAL pixel zoom. A constant 13px marker
        // covered entire map tiles when zoomed far out (see screenshots).
        float iconScale = (float) Math.clamp(zoomPixels / 7.0, .30, 1.0);
        g.pose().pushPose();
        g.pose().translate(sx, sy, 0);
        g.pose().scale(iconScale, iconScale, 1);
        if (glow) rounded(g, -8, -8, 17, 17, 5, 0x80ffe4a0);
        rounded(g, -6, -6, 13, 13, cfg.style == Style.PIXEL ? 2 : 4, 0xfff6e7c6);
        g.drawCenteredString(font, glyph, 0, -4, 0xff000000 | color);
        g.pose().popPose();
        // At distant zoom, labels obscure more terrain than the marker.
        if (zoomPixels >= 5.5) {
            g.pose().pushPose();
            g.pose().translate(sx + 8 * iconScale, sy, 0);
            g.pose().scale((float) (ui * iconScale), (float) (ui * iconScale), 1);
            String text = shorten(label, 100);
            int w = font.width(text) + 8;
            rounded(g, 0, -7, w, 15, 2, PANEL);
            rounded(g, 1, -6, w - 2, 13, 1, 0xfff1e6ca);
            g.drawString(font, text, 4, -4, INSET, false);
            g.pose().popPose();
        }
    }

    private static String structureName(String type) {
        if (type.contains("village")) return "村庄";
        return switch (type) {
            case "minecraft:desert_pyramid" -> "沙漠神殿";
            case "minecraft:jungle_temple" -> "丛林神庙";
            case "minecraft:woodland_mansion" -> "林地府邸";
            case "minecraft:pillager_outpost" -> "掠夺者前哨站";
            case "minecraft:swamp_hut" -> "沼泽小屋";
            default -> "地表结构";
        };
    }

    private void drawBoundary(GuiGraphics g, List<Point> points, boolean closed, int color) {
        int edges = closed && points.size() > 2 ? points.size() : points.size() - 1;
        for (int i = 0; i < edges; i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size());
            line(g, screenX(a.x()), screenZ(a.z()), screenX(b.x()), screenZ(b.z()), color, 1);
        }
    }

    private void toolbar(GuiGraphics g) {
        int x = w() - 287, y = 13, step = 38;
        card(g, x, y, 274, 41, 8);
        for (int i = 0; i < TOOLS.length; i++) {
            int bx = x + 5 + step * i, item = i;
            boolean selected = switch (i) {
                case 0 -> panel == Panel.LAYERS; case 1 -> panel == Panel.MARKERS; case 2 -> panel == Panel.REGIONS;
                case 3 -> panel == Panel.ATLAS; case 5 -> panel == Panel.INFO; default -> false;
            };
            boolean hover = inside(mouseX, mouseY, bx, y + 4, 35, 33);
            if (selected || hover) rounded(g, bx, y + 4, 35, 33, 5, selected ? CONTROL : HOVER);
            toolGlyph(g, bx + 17, y + 14, i);
            g.drawString(font, TOOLS[i], bx + 17 - font.width(TOOLS[i]) / 2,
                    y + 24, selected ? GOLD : CREAM, false);
            hits.add(new Hit(bx, y + 4, 35, 33, () -> tool(item)));
        }
        // No standalone lower-right target icon: it duplicates the
        // map centering affordance, doesn't exist in the approved prototype.
        // HOME remains a discoverable keyboard shortcut for recentering.
    }

    private void toolGlyph(GuiGraphics g, int x, int y, int i) {
        int c = CREAM;
        switch (i) {
            case 0 -> {
                for (int v = -4; v <= 3; v += 3) {
                    line(g, x - 5, y + v, x, y + v - 3, c, 1);
                    line(g, x, y + v - 3, x + 5, y + v, c, 1);
                    line(g, x + 5, y + v, x, y + v + 3, c, 1);
                    line(g, x, y + v + 3, x - 5, y + v, c, 1);
                }
            }
            case 1 -> {
                g.renderOutline(x - 4, y - 5, 9, 8, c);
                line(g, x - 3, y + 2, x, y + 6, c, 1); line(g, x + 3, y + 2, x, y + 6, c, 1);
                g.fill(x - 1, y - 3, x + 2, y, c);
            }
            case 2 -> {
                line(g, x - 5, y + 3, x - 3, y - 4, c, 1); line(g, x - 3, y - 4, x + 5, y - 3, c, 1);
                line(g, x + 5, y - 3, x + 3, y + 5, c, 1); line(g, x + 3, y + 5, x - 5, y + 3, c, 1);
                g.fill(x - 6, y + 2, x - 3, y + 5, c); g.fill(x + 4, y - 4, x + 7, y - 1, c);
            }
            case 3 -> {
                g.renderOutline(x - 7, y - 5, 7, 11, c); g.renderOutline(x, y - 5, 7, 11, c);
            }
            case 4 -> {
                for (int j = 0; j < 3; j++) {
                    g.fill(x - 6, y - 4 + j * 4, x + 7, y - 3 + j * 4, c);
                    int xx = x - 4 + j * 4;
                    g.fill(xx, y - 5 + j * 4, xx + 2, y - 2 + j * 4, c);
                }
            }
            case 5 -> {
                g.renderOutline(x - 5, y - 5, 11, 11, c);
                g.drawCenteredString(font, "i", x, y - 4, c);
            }
            case 6 -> {
                line(g, x - 4, y - 4, x + 4, y + 4, c, 1); line(g, x + 4, y - 4, x - 4, y + 4, c, 1);
            }
            default -> {
                g.renderOutline(x - 5, y - 5, 11, 11, c);
                g.fill(x - 8, y, x - 3, y + 1, c); g.fill(x + 4, y, x + 9, y + 1, c);
                g.fill(x, y - 8, x + 1, y - 3, c); g.fill(x, y + 4, x + 1, y + 9, c);
                g.fill(x - 1, y - 1, x + 2, y + 2, GOLD);
            }
        }
    }

    private void tool(int i) {
        selection = null; menu = Menu.NONE;
        if (i == 6) { onClose(); return; }
        if (i == 4) {
            panel = Panel.NONE;
            settingsTab = SettingsTab.MAP;
            settingsScroll = 0;
            MapNetwork.MaterialRates r = CartographClient.settings().rates();
            for (int j = 0; j < 4; j++) { inputs[j] = r.input(j); costs[j] = r.cost(j); }
            permanentFlags = r.permanentFlags();
            newSheetSideBlocks = r.pageSideBlocks(); ratesDirty = false;
            MapNetwork.PerformanceSettings performance = CartographClient.performance();
            preferredWorkers = performance.workers();
            preferredBudgetMs = performance.budgetMs();
            performanceDirty = false;
            CartographClient.refreshControl(4);
            dialog = Dialog.SETTINGS;
            return;
        }
        Panel next = switch (i) {
            case 0 -> Panel.LAYERS; case 1 -> Panel.MARKERS; case 2 -> Panel.REGIONS;
            case 3 -> Panel.ATLAS; default -> Panel.INFO;
        };
        panel = panel == next ? Panel.NONE : next; scroll = 0;
        if (panel == Panel.REGIONS || panel == Panel.MARKERS) CartographClient.requestAnnotations();
    }

    private void pixelTools(GuiGraphics g) {
        if (cfg.style != Style.PIXEL) return;
        int top = w() < 545 ? 63 : 13;
        card(g, 14, top, 230, 31, 7);
        g.drawString(font, "像素地形", 23, top + 11, GOLD);
        String[] items = {"上一版", "新版像素", "左右对照"};
        for (int i = 0; i < items.length; i++) {
            PixelView state = PixelView.values()[i];
            button(g, 75 + i * 54, top + 5, 51, 21, items[i], pixelView == state, () -> pixelView = state);
        }
        if (pixelView == PixelView.SPLIT) {
            int x = (int) (split * w());
            g.fill(x, 0, x + 1, h(), 0xd9f2dda5);
            rounded(g, x - 9, h() / 2 - 15, 18, 30, 5, PANEL);
            g.drawCenteredString(font, "↔", x, h() / 2 - 4, GOLD);
            g.drawString(font, "上一版", 17, top + 39, CREAM);
            g.drawString(font, "新版像素", w() - 64, top + 39, CREAM);
            hits.add(new Hit(x - 10, h() / 2 - 16, 21, 32, () -> movingSplit = true));
        }
    }

    private void statusBox(GuiGraphics g, int mx, int my) {
        int x = selection != null ? selection.x() : worldX(mx);
        int z = selection != null ? selection.z() : worldZ(my);
        String main = CartographClient.dimensionName(CartographClient.dimension())
                + "   X " + x + " · Z " + z + "   " + String.format(Locale.ROOT, "%.2f×", zoomPixels);
        MapNetwork.Page c = CartographClient.coverage();
        String hint;
        if (c != null && !c.anchorDimension().equals(CartographClient.dimension()))
            hint = "地图册绑定" + CartographClient.dimensionName(c.anchorDimension());
        else if (c != null && !CartographClient.covered(pageX(viewX), pageX(viewZ)))
            hint = "超出地图册范围 · 按 Home 定位";
        else if (CartographClient.refreshProgress().state() == 1)
            hint = "后台刷新已测绘区块 · " + CartographClient.refreshProgress().done()
                    + " / " + CartographClient.refreshProgress().total();
        else if (CartographClient.awaitingPage())
            hint = "正在补齐当前视野 · 已缓存 " + CartographClient.cachedCount() + " 瓦片";
        else if (!CartographClient.state().inkEnabled()) hint = "在地图册材料槽投入墨囊即可揭开地图";
        else hint = status.isBlank() ? "左键详情 · 右键操作 · 拖动平移 · 滚轮缩放 · Home定位" : status;
        int tw = Math.min(w() - 62, Math.max(font.width(main), font.width(hint)) + 20);
        card(g, 14, h() - 48, tw, 35, 7);
        g.drawString(font, shorten(main, tw - 16), 23, h() - 41, GOLD);
        g.drawString(font, shorten(hint, tw - 16), 23, h() - 25, MUTED);
    }

    private void renderPanel(GuiGraphics g) {
        int width = Math.min(228, w() - 28), x = w() - width - 14, y = 62;
        int height = Math.min(h() - y - 60, panel == Panel.ATLAS ? 231 : panel == Panel.INFO ? 184 : 276);
        card(g, x, y, width, height, 8);
        String title = switch (panel) {
            case LAYERS -> "地图图层"; case MARKERS -> "我的标点"; case REGIONS -> "区域命名";
            case ATLAS -> "地图册 · 资源"; case INFO -> "地图信息"; default -> "";
        };
        g.drawString(font, title, x + 13, y + 15, GOLD);
        button(g, x + width - 25, y + 7, 18, 17, "×", false, () -> panel = Panel.NONE);
        hits.add(0, new Hit(x, y, width, height, () -> {}));
        int left = x + 12, inner = width - 24, row = y + 34;
        switch (panel) {
            case LAYERS -> {
                for (int i = 0; i < LAYERS.length; i++) {
                    int flag = i;
                    boolean auto = flag == CONTOURS && cfg.style == Style.PIXEL;
                    check(g, left, row + i * 26, inner, auto ? "方块高差阴影（自动）" : LAYERS[i],
                            auto || cfg.layer(flag), auto ? null : () -> cfg.toggleLayer(flag));
                }
            }
            case ATLAS -> {
                AtlasState state = CartographClient.state();
                MapNetwork.Page c = CartographClient.coverage();
                int sheetSide = c == null ? MapSheetCoverage.DEFAULT_BLOCKS : c.sheetSideBlocks();
                int count = CartographClient.pageCount() * MapSheetCoverage.areaChunksPerSheet(sheetSide);
                textRow(g, left, row, inner, "地图页", CartographClient.pageCount() + " 页");
                textRow(g, left, row + 18, inner, RESOURCES[3], balance(state.paperUnits(), 3, state.paperEnabled()));
                textRow(g, left, row + 36, inner, RESOURCES[0], balance(state.inkUnits(), 0, state.inkEnabled()));
                textRow(g, left, row + 54, inner, RESOURCES[1], balance(state.pigmentUnits(), 1, state.colorEnabled()));
                textRow(g, left, row + 72, inner, RESOURCES[2], balance(state.glowUnits(), 2, state.glowEnabled()));
                textRow(g, left, row + 90, inner, "指南针", state.compassInstalled() ? "已装入" : "未装入");
                progress(g, left, row + 110, inner, "测绘覆盖", c == null ? 0 : c.totalSurveyed(), count, 0xff93ad8b);
                progress(g, left, row + 136, inner, "彩色覆盖", c == null ? 0 : c.totalColored(), count, 0xffb1a6d2);
                button(g, left, row + 166, inner, 21, "打开材料槽", true, this::openMaterials);
            }
            case MARKERS -> {
                button(g, left, row, inner, 24, "＋ 新增标点", true, () -> openMarker(null, world(viewX), world(viewZ)));
                List<AtlasMarker> items = markers();
                int rows = Math.max(0, (height - 68) / 26), start = Math.clamp(scroll, 0, Math.max(0, items.size() - rows));
                for (int i = start; i < Math.min(items.size(), start + rows); i++) {
                    AtlasMarker m = items.get(i);
                    button(g, left, row + 31 + (i - start) * 26, inner, 22,
                            markerGlyph(m.icon()) + "  " + m.name(), false, () -> {
                                focus(m.x(), m.z()); selection = new Selection(m.x(), m.z(), m, null, null);
                            });
                }
            }
            case REGIONS -> {
                button(g, left, row, inner, 24, "＋ 在地图上圈选区域", true, () -> startRegion(null));
                List<NamedRegion> items = CartographClient.regions();
                int rows = Math.max(0, (height - 68) / 26), start = Math.clamp(scroll, 0, Math.max(0, items.size() - rows));
                for (int i = start; i < Math.min(items.size(), start + rows); i++) {
                    NamedRegion r = items.get(i); Point a = r.labelPosition();
                    button(g, left, row + 31 + (i - start) * 26, inner, 22,
                            r.name() + " · " + NamedRegion.TYPES[r.type()], false, () -> {
                                focus(a.x(), a.z()); selection = new Selection(a.x(), a.z(), null, r, null);
                            });
                }
            }
            case INFO -> {
                textRow(g, left, row, inner, "维度", CartographClient.dimensionName(CartographClient.dimension()));
                textRow(g, left, row + 22, inner, "风格", cfg.style.label);
                textRow(g, left, row + 44, inner, "已加载标点", Integer.toString(markers().size()));
                textRow(g, left, row + 66, inner, "命名区域", Integer.toString(CartographClient.regions().size()));
                MapNetwork.Page current = CartographClient.page(pageX(viewX), pageX(viewZ));
                textRow(g, left, row + 88, inner, "当前页采样", current == null ? "未请求" : current.scanned() + " / 64");
                textRow(g, left, row + 110, inner, "已生成区块", current == null ? "—" : current.generated() + " / 64");
                textRow(g, left, row + 132, inner, "已绘制区块", current == null ? "—" : current.painted() + " / 64");
            }
            default -> { }
        }
    }

    private List<AtlasMarker> markers() {
        return CartographClient.markers().stream().sorted(Comparator.comparing(AtlasMarker::name).thenComparing(AtlasMarker::id)).toList();
    }

    private String balance(int units, int i, boolean enabled) {
        return enabled && CartographClient.settings().rates().permanent(i) ? "永久"
                : String.format(Locale.ROOT, "%.1f", units / 10.0);
    }

    private void progress(GuiGraphics g, int x, int y, int w, String label, int value, int maximum, int color) {
        textRow(g, x, y, w, label, value + " / " + maximum);
        rounded(g, x, y + 12, w, 5, 2, INSET);
        rounded(g, x, y + 12, (int) (w * Math.min(1, value / (double) Math.max(1, maximum))), 5, 2, color);
    }

    private void renderSelection(GuiGraphics g) {
        int width = Math.min(210, w() - 18), height = selection.region() != null ? 153 : 124;
        int x = Math.clamp((int) ux(screenX(selection.x())) + 11, 9, w() - width - 9);
        int y = Math.clamp((int) ux(screenZ(selection.z())) + 11, 10, h() - height - 11);
        card(g, x, y, width, height, 7);
        hits.add(new Hit(x, y, width, height, () -> {}));
        Selection selected = selection;
        String title = selected.marker() != null ? selected.marker().name()
                : selected.region() != null ? selected.region().name()
                : selected.structure() != null ? structureName(selected.structure().type()) : "地图位置";
        g.drawString(font, shorten(title, width - 46), x + 13, y + 12, GOLD);
        button(g, x + width - 24, y + 6, 17, 18, "×", false, () -> selection = null);
        textRow(g, x + 12, y + 33, width - 24, "坐标", "X " + selected.x() + "  Z " + selected.z());
        MapNetwork.Page data = pageAt(selected.x(), selected.z());
        int at = Math.floorMod(selected.z(), 128) * 128 + Math.floorMod(selected.x(), 128);
        String surface = "未揭开";
        if (data != null && data.heights().length == MapNetwork.Page.PIXELS && data.heights()[at] != TerrainRelief.UNKNOWN_HEIGHT)
            surface = data.waterDepths()[at] > 0 ? "水深 " + Byte.toUnsignedInt(data.waterDepths()[at])
                    : "地表高度 " + data.heights()[at];
        textRow(g, x + 12, y + 48, width - 24, "地表", surface);
        if (selected.region() != null) {
            NamedRegion r = selected.region();
            textRow(g, x + 12, y + 63, width - 24, NamedRegion.TYPES[r.type()], r.boundary().size() + " 个顶点");
            g.drawString(font, shorten(r.note(), width - 24), x + 12, y + 78, MUTED);
            int half = (width - 29) / 2;
            button(g, x + 12, y + 96, half, 20, "定位", false, () -> focus(selected.x(), selected.z()));
            button(g, x + 17 + half, y + 96, half, 20, "编辑名称和备注", false, () -> openRegion(r, r.boundary()));
            button(g, x + 12, y + 122, half, 20, "调整边界", false, () -> startRegion(r));
            button(g, x + 17 + half, y + 122, half, 20, "删除", false, () -> confirm("删除区域 " + r.name() + "？",
                    () -> CartographClient.sendRegion(new MapNetwork.EditRegion(CartographClient.atlasId(), r.id(),
                            "", r.type(), r.color(), "", List.of(), true))));
        } else if (selected.marker() != null) {
            AtlasMarker m = selected.marker();
            int part = (width - 36) / 3;
            button(g, x + 12, y + 83, part, 24, "定位", false, () -> focus(m.x(), m.z()));
            button(g, x + 18 + part, y + 83, part, 24, "编辑", false, () -> openMarker(m, m.x(), m.z()));
            button(g, x + 24 + 2 * part, y + 83, part, 24, "删除", false, () -> confirm("删除标点 " + m.name() + "？",
                    () -> CartographClient.sendMarker(new MapNetwork.EditMarker(CartographClient.atlasId(), m.id(),
                            m.x(), m.z(), m.name(), m.icon(), m.color(), m.glowing(), true))));
        } else button(g, x + 12, y + 83, width - 24, 24, "定位", false, () -> focus(selected.x(), selected.z()));
    }

    private void renderMenu(GuiGraphics g) {
        int width = 132, height = menu == Menu.MAP ? 102 : 32;
        int x = Math.clamp(contextX, 5, w() - width - 5), y = Math.clamp(contextY, 5, h() - height - 5);
        card(g, x, y, width, height, 6);
        if (menu == Menu.VERTEX) {
            button(g, x + 6, y + 6, width - 12, 20, "删除顶点", false, () -> {
                if (draft != null && draft.size() > 3 && menuVertex >= 0) draft.remove(menuVertex);
                else toast("区域至少需要三个顶点", true);
                menu = Menu.NONE;
            });
            return;
        }
        String[] labels = {"在此添加标点", "在此开始圈选区域", "查看位置详情", "以该处居中"};
        for (int i = 0; i < labels.length; i++) {
            int act = i;
            button(g, x + 6, y + 5 + i * 23, width - 12, 21, labels[i], false, () -> {
                menu = Menu.NONE;
                switch (act) {
                    case 0 -> openMarker(null, contextWorldX, contextWorldZ);
                    case 1 -> {
                        startRegion(null); draft.add(new Point(contextWorldX, contextWorldZ));
                    }
                    case 2 -> select(contextWorldX, contextWorldZ, false);
                    case 3 -> { manuallyMoved = true; focus(contextWorldX, contextWorldZ); }
                    default -> { }
                }
            });
        }
    }

    private void drawGuide(GuiGraphics g) {
        int x = (w() - 322) / 2, y = 63;
        card(g, x, y, 322, 58, 8);
        g.drawString(font, adjustVertices ? "拖动顶点 · 双击边线加点 · 右键删点" : "逐点圈出区域边界，至少三个顶点",
                x + 13, y + 10, CREAM);
        g.drawString(font, draft.size() + " 个顶点", x + 258, y + 10, GOLD);
        button(g, x + 9, y + 31, 71, 20, "撤销顶点", false, () -> { if (!draft.isEmpty()) draft.removeLast(); });
        button(g, x + 85, y + 31, 71, 20, adjustVertices ? "继续圈选" : "调整顶点", adjustVertices,
                () -> { if (draft.size() >= 3) adjustVertices = !adjustVertices; });
        button(g, x + 161, y + 31, 71, 20, "完成圈选", true, this::finishDraft);
        button(g, x + 237, y + 31, 74, 20, "取消", false, this::discardDraft);
    }

    private void startRegion(NamedRegion existing) {
        panel = Panel.NONE; selection = null; menu = Menu.NONE;
        editingRegion = existing;
        draft = existing == null ? new ArrayList<>() : new ArrayList<>(existing.boundary());
        adjustVertices = existing != null; grabbedVertex = -1;
    }

    private void discardDraft() { draft = null; grabbedVertex = -1; adjustVertices = false; menu = Menu.NONE; }

    private void finishDraft() {
        if (!NamedRegion.simplePolygon(draft)) { toast("至少三个顶点，区域边界不能交叉", true); return; }
        openRegion(editingRegion, List.copyOf(draft));
    }

    private void select(int x, int z, boolean snap) {
        AtlasMarker marker = null;
        SurfaceStructure structure = null;
        if (snap) {
            double best = 10;
            for (MapNetwork.Page page : CartographClient.cachedPages()) {
                if (cfg.layer(WAYPOINTS)) for (AtlasMarker m : page.markers()) {
                    double distance = Math.hypot((m.x() - x) * zoom, (m.z() - z) * zoom);
                    if (distance < best && CartographClient.surveyed(m.x(), m.z())) { marker = m; structure = null; best = distance; }
                }
                if (cfg.layer(STRUCTURES)) for (SurfaceStructure p : page.structures()) {
                    double distance = Math.hypot((p.x() - x) * zoom, (p.z() - z) * zoom);
                    if (distance < best) { structure = p; marker = null; best = distance; }
                }
            }
        }
        if (marker != null) selection = new Selection(marker.x(), marker.z(), marker, null, null);
        else if (structure != null) selection = new Selection(structure.x(), structure.z(), null, null, structure);
        else {
            NamedRegion region = null;
            if (CartographClient.surveyed(x, z)) for (NamedRegion r : CartographClient.regions())
                if (r.contains(x, z)) { region = r; break; }
            selection = new Selection(x, z, null, region, null);
        }
    }

    private int modalW() { return Math.min(486, w() - 24); }
    private int modalH() { return switch (dialog) {
        case SETTINGS -> switch (settingsTab) {
            case MAP -> 366; case MINI -> 312; case COST -> 381; case CACHE -> 353;
        };
        case MATERIALS -> 366;
        case MARKER -> 291;
        case REGION -> 235;
        case CONFIRM -> 110;
        default -> 0;
    }; }
    private int modalX() { return (w() - modalW()) / 2; }
    private int modalY() {
        return (h() - (dialog == Dialog.SETTINGS ? visibleSettingsH() : modalH())) / 2;
    }
    private int visibleSettingsH() {
        return Math.min(modalH(), Math.max(110, h() - 16));
    }
    private int maxSettingsScroll() {
        return Math.max(0, modalH() - visibleSettingsH());
    }

    private void renderDialog(GuiGraphics g) {
        g.fill(0, 0, w(), h(), 0x8f17271f);
        int x = modalX(), y = modalY(), w = modalW(), h = modalH();
        int visibleH = dialog == Dialog.SETTINGS ? visibleSettingsH() : h;
        card(g, x, y, w, visibleH, 9);
        String title = switch (dialog) {
            case SETTINGS -> "地图设置";
            case MATERIALS -> "地图册 · 材料槽";
            case MARKER -> editingMarker == null ? "新增地图标点" : "编辑标点";
            case REGION -> editingRegion == null ? "为这片区域命名" : "编辑区域名称";
            case CONFIRM -> "确认删除";
            default -> "";
        };
        g.drawString(font, "CARTOGRAPH", x + 14, y + 11, MUTED);
        g.drawString(font, title, x + 14, y + 26, GOLD);
        button(g, x + w - 29, y + 9, 20, 20, "×", false, this::closeDialog);
        switch (dialog) {
            case SETTINGS -> {
                settingsScroll = Math.clamp(settingsScroll, 0, maxSettingsScroll());
                // Tabs are navigation, not scrolling contents: they stay
                // visible when the lower settings require a scroll.
                settingsTabs(g, x, y, w);
                int bodyTop = y + 76, bodyBottom = y + visibleH - 34;
                int startHit = hits.size();
                g.flush();
                // GuiGraphics scissor uses actual GUI coordinates, whereas
                // cartograph's map UI is additionally scaled via PoseStack.
                g.enableScissor((int) (x * ui), (int) (bodyTop * ui),
                        (int) ((x + w) * ui), (int) (bodyBottom * ui));
                settings(g, x, y - settingsScroll, w, h);
                g.flush();
                g.disableScissor();
                // Hidden options must not still be clickable under the
                // fixed title bar, completion button, or outside the dialog.
                for (int i = hits.size() - 1; i >= startHit; i--) {
                    Hit hit = hits.get(i);
                    int top = Math.max(hit.y(), bodyTop), bottom = Math.min(hit.y() + hit.h(), bodyBottom);
                    if (bottom <= top) hits.remove(i);
                    else hits.set(i, new Hit(hit.x(), top, hit.w(), bottom - top, hit.action()));
                }
                if (maxSettingsScroll() > 0) {
                    int sx = x + w - 5, sy = bodyTop + (bodyBottom - bodyTop - 20)
                            * settingsScroll / maxSettingsScroll();
                    rounded(g, sx, bodyTop, 3, bodyBottom - bodyTop, 1, CONTROL);
                    rounded(g, sx, sy, 3, 20, 1, GOLD);
                }
                // Fixed actions remain usable, irrespective of scrolling.
                if (settingsTab == SettingsTab.MINI)
                    button(g, x + 14, y + visibleH - 30, 93, 21,
                            "恢复紧凑默认", false, cfg::resetHud);
                button(g, x + w - 85, y + visibleH - 30, 71, 21,
                        "完成", true, this::closeDialog);
            }
            case MATERIALS -> materials(g, x, y, w, h);
            case MARKER -> markerEditor(g, x, y, w, h);
            case REGION -> regionEditor(g, x, y, w, h);
            case CONFIRM -> {
                g.drawString(font, shorten(confirmation, w - 30), x + 14, y + 50, CREAM);
                button(g, x + w - 163, y + 74, 70, 22, "取消", false, this::closeDialog);
                button(g, x + w - 84, y + 74, 70, 22, "删除", true, () -> {
                    Runnable action = confirmed; closeDialog();
                    if (action != null) action.run();
                });
            }
            default -> { }
        }
    }

    private void settingsTabs(GuiGraphics g, int x, int y, int w) {
        int inner = w - 28, left = x + 14;
        String[] tabs = {"大地图", "小地图", "耗材倍率", "缓存与性能"};
        int tabWidth = (inner - 15) / 4;
        for (int i = 0; i < tabs.length; i++) {
            SettingsTab t = SettingsTab.values()[i];
            button(g, left + i * (tabWidth + 5), y + 46, tabWidth, 23, tabs[i], settingsTab == t,
                    () -> {
                        settingsTab = t;
                        settingsScroll = 0;
                        if (t == SettingsTab.CACHE) CartographClient.refreshControl(4);
                    });
        }
    }

    private void settings(GuiGraphics g, int x, int y, int w, int h) {
        int inner = w - 28, left = x + 14, third = (inner - 10) / 3;
        if (settingsTab == SettingsTab.MAP) {
            g.drawString(font, "地图风格", left, y + 81, GOLD);
            ResourceLocation thumbnail = previewTexture();
            for (int i = 0; i < 3; i++) {
                Style style = Style.values()[i];
                int bx = left + i * (third + 5), by = y + 96;
                rounded(g, bx, by, third, 70, 6, style == cfg.style ? GOLD : CONTROL);
                rounded(g, bx + 1, by + 1, third - 2, 68, 5, INSET);
                if (thumbnail != null) PixelPageTexture.thumbnail(g, thumbnail, style, bx + 5, by + 5, third - 10, 38);
                else rounded(g, bx + 5, by + 5, third - 10, 38, 3, 0xff000000 | PixelPageTexture.PAPER);
                String label = shorten(style.label, third - 7);
                g.drawString(font, label, bx + (third - font.width(label)) / 2, by + 52, CREAM, false);
                hits.add(new Hit(bx, by, third, 70, () -> { cfg.style = style; cfg.save(); }));
            }
            check(g, left, y + 176, inner, "显示区域名称", cfg.layer(REGIONS), () -> cfg.toggleLayer(REGIONS));
            check(g, left, y + 199, inner, "显示区域边界", cfg.layer(BOUNDARIES), () -> cfg.toggleLayer(BOUNDARIES));
            boolean pixel = cfg.style == Style.PIXEL;
            check(g, left, y + 222, inner, pixel ? "方块高差阴影（自动）" : "显示等高线",
                    pixel || cfg.layer(CONTOURS), pixel ? null : () -> cfg.toggleLayer(CONTOURS));
            boolean editable = CartographClient.settings().editable();
            g.drawString(font, "单次扩页覆盖范围（默认 512×512）", left, y + 256, GOLD);
            int[] sides = {128, 256, 512, 1024, 2048};
            int cell = (inner - 4 * 5) / sides.length;
            for (int i = 0; i < sides.length; i++) {
                int size = sides[i], bx = left + i * (cell + 5);
                button(g, bx, y + 274, cell, 23, size + "²",
                        newSheetSideBlocks == size,
                        editable ? () -> { newSheetSideBlocks = size; ratesDirty = true; } : null);
            }
            MapNetwork.Page activePage = CartographClient.coverage();
            int activeSide = activePage == null ? MapSheetCoverage.DEFAULT_BLOCKS : activePage.sheetSideBlocks();
            g.drawString(font, shorten("当前地图册：" + activeSide + "×" + activeSide
                            + " · 已有地图册范围保持不变", inner),
                    left, y + 312, MUTED);
        } else if (settingsTab == SettingsTab.MINI) {
            int half = (inner - 7) / 2;
            g.drawString(font, "小地图与手持地图共用图层、方位及地图风格", left, y + 79, GOLD, false);
            for (int i = 0; i < HUD_FLAGS.length; i++) {
                int flag = i;
                check(g, left + i % 2 * (half + 7), y + 94 + i / 2 * 24, half,
                        HUD_FLAGS[i], cfg.hud(flag), () -> cfg.toggleHud(flag));
            }
            g.drawString(font, "悬浮小地图形状", left, y + 170, GOLD, false);
            button(g, left + 100, y + 165, 52, 23, "方形", !cfg.round,
                    () -> { cfg.round = false; cfg.save(); });
            button(g, left + 158, y + 165, 52, 23, "圆形", cfg.round,
                    () -> { cfg.round = true; cfg.save(); });
            g.drawString(font, "缩放 " + String.format(Locale.ROOT, "%.1f×", cfg.hudZoom),
                    left, y + 201, CREAM, false);
            slider(g, left + 92, y + 195, inner - 95, (cfg.hudZoom - .5) / 3.5,
                    n -> { cfg.hudZoom = Math.round((.5 + n * 3.5) * 10) / 10.0; cfg.save(); });
            button(g, left, y + 219, inner, 23, "调整悬浮小地图位置与大小", true,
                    () -> minecraft.setScreen(new HudAdjustScreen(this)));
            g.drawString(font, shorten("手持地图：主手居中、副手左下；位置不需调整", inner),
                    left, y + 252, MUTED, false);

        } else if (settingsTab == SettingsTab.COST) {
            if (!ratesDirty) {
                MapNetwork.MaterialRates rates = CartographClient.settings().rates();
                for (int i = 0; i < 4; i++) { inputs[i] = rates.input(i); costs[i] = rates.cost(i); }
                permanentFlags = rates.permanentFlags();
            newSheetSideBlocks = rates.pageSideBlocks();
            }
            boolean editable = CartographClient.settings().editable();
            for (int i = 0; i < 4; i++) {
                int row = y + 77 + i * 65, slot = i;
                rounded(g, left, row, inner, 60, 5, INSET);
                g.drawString(font, RESOURCES[i], left + 8, row + 7, GOLD);
                int half = (inner - 23) / 2;
                rate(g, left + 8, row + 21, half, "投入", inputs[i], editable,
                        value -> { inputs[slot] = value; ratesDirty = true; });
                rate(g, left + 15 + half, row + 21, half, "消耗", costs[i], editable,
                        value -> { costs[slot] = value; ratesDirty = true; });
                check(g, left + 7, row + 37, inner - 14, PERMANENT[i] + "（首次材料解锁后生效）",
                        (permanentFlags & 1 << i) != 0,
                        editable ? () -> { permanentFlags ^= 1 << slot; ratesDirty = true; } : null);
            }
        } else {
            boolean editable = CartographClient.performance().editable();
            g.drawString(font, "后台磁盘解析线程数", left, y + 83, GOLD, false);
            int[] threads = {2, 4, 8};
            for (int i = 0; i < threads.length; i++) {
                int value = threads[i], bx = left + i * (third + 5);
                button(g, bx, y + 98, third, 23, value + " 线程",
                        preferredWorkers == value,
                        editable ? () -> { preferredWorkers = value; performanceDirty = true; } : null);
            }
            g.drawString(font, "游戏主线程扫描预算（不包括后台线程）", left, y + 135, GOLD, false);
            int[] budgets = {2, 4, 8};
            String[] names = {"低占用", "均衡", "快速"};
            for (int i = 0; i < budgets.length; i++) {
                int budget = budgets[i], bx = left + i * (third + 5);
                button(g, bx, y + 150, third, 23, names[i] + " " + budget + "ms",
                        preferredBudgetMs == budget,
                        editable ? () -> { preferredBudgetMs = budget; performanceDirty = true; } : null);
            }
            MapNetwork.RefreshProgress progress = CartographClient.refreshProgress();
            String stateLabel = switch (progress.state()) {
                case 1 -> "正在刷新"; case 2 -> "已暂停";
                case 3 -> "已完成"; case 4 -> "已取消"; default -> "未开始";
            };
            g.drawString(font, "刷新已测绘区域（无需墨水、纸值或重新解锁）", left, y + 193, GOLD, false);
            g.drawString(font, shorten("任务：" + stateLabel + " · 已完成 " + progress.done()
                            + " / " + progress.total() + " 区块", inner),
                    left, y + 212, CREAM, false);
            int fill = progress.total() == 0 ? 0 :
                    (int) Math.round((inner - 6) * progress.done() / (double) progress.total());
            rounded(g, left, y + 229, inner, 8, 3, CONTROL);
            rounded(g, left, y + 229, fill, 8, 3, GOLD);
            button(g, left, y + 245, inner, 23, "刷新加载全部已测绘地图", false,
                    () -> CartographClient.refreshControl(0));
            int half = (inner - 8) / 2;
            boolean running = progress.state() == 1, paused = progress.state() == 2;
            button(g, left, y + 273, half, 23, running ? "暂停刷新" : "继续刷新", false,
                    running || paused ? () -> CartographClient.refreshControl(running ? 1 : 2) : null);
            button(g, left + half + 8, y + 273, half, 23, "取消任务", false,
                    running || paused ? () -> CartographClient.refreshControl(3) : null);
            g.drawString(font, shorten("失败 / 缺失 " + progress.failed()
                            + " · 显示旧缓存，后台并行更新实际世界", inner),
                    left, y + 304, MUTED, false);
        }
    }

    private ResourceLocation previewTexture() {
        ResourceLocation center = CartographClient.texture(pageX(viewX), pageX(viewZ));
        if (center != null) return center;
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            ResourceLocation id = CartographClient.texture(page.pageX(), page.pageZ());
            if (id != null) return id;
        }
        return null;
    }

    private void rate(GuiGraphics g, int x, int y, int w, String label, int value, boolean enabled,
                      java.util.function.IntConsumer set) {
        g.drawString(font, label + " " + String.format(Locale.ROOT, "%.1f", value / 10.0), x, y + 4, CREAM);
        int offset = 49;
        slider(g, x + offset, y, w - offset, (value - 1) / 99.0,
                enabled ? n -> set.accept(1 + (int) Math.round(n * 99)) : null);
    }

    private void slider(GuiGraphics g, int x, int y, int width, double percent, DoubleConsumer set) {
        if (width < 6) return;
        rounded(g, x, y + 6, width, 5, 2, CONTROL);
        int fill = (int) (percent * width);
        rounded(g, x, y + 6, fill, 5, 2, set == null ? OUTLINE : GOLD);
        rounded(g, x + fill - 3, y + 3, 7, 11, 3, set == null ? MUTED : CREAM);
        if (set != null) hits.add(new Hit(x - 4, y, width + 8, 17, () -> {
            activeSlider = new Slider(x, width, set); activeSlider.update(mouseX);
        }));
    }

    private void openMaterials() {
        materialNotice = "选择材料进行投入，服务器确认后显示结果";
        materialNoticeError = false;
        dialog = Dialog.MATERIALS;
    }

    private void requestRefill(int material) {
        materialNotice = "正在提交" + MATERIAL_NAMES[material] + "，请查看确认结果…";
        materialNoticeError = false;
        materialPending = true;
        requestStartedAtTick = ticks;
        CartographClient.sendRefill(material);
    }

    private void materials(GuiGraphics g, int x, int y, int w, int h) {
        int left = x + 14, inner = w - 28;
        AtlasState state = CartographClient.state();
        MapNetwork.MaterialRates rates = CartographClient.settings().rates();
        g.drawString(font, "消耗品", left, y + 46, GOLD);
        int half = (inner - 7) / 2, third = (inner - 14) / 3;
        boolean inkPermanent = state.inkEnabled() && rates.permanent(0);
        boolean glowPermanent = state.glowEnabled() && rates.permanent(2);
        slot(g, left, y + 60, half, 0, inkPermanent ? "已永久启用" : refillHint(rates, 0), inkPermanent);
        slot(g, left + half + 7, y + 60, half, 1, glowPermanent ? "已永久启用" : refillHint(rates, 2), glowPermanent);
        g.drawString(font, shorten("彩色染料 · 红黄蓝各一份 · 每组 +" + refillAmount(rates, 1), inner),
                left, y + 110, GOLD);
        int[] dye = {state.redDyes(), state.yellowDyes(), state.blueDyes()};
        for (int i = 0; i < 3; i++) {
            boolean permanent = state.colorEnabled() && rates.permanent(1);
            slot(g, left + i * (third + 7), y + 125, third, i + 2,
                    permanent ? "永久解锁" : dye[i] > 0 ? "已投入" : "消耗 1 个", permanent || dye[i] > 0);
        }
        g.drawString(font, "地图纸 · 投入空地图", left, y + 175, GOLD);
        boolean paperPermanent = state.paperEnabled() && rates.permanent(3);
        slot(g, left, y + 190, inner, 6,
                paperPermanent ? "已永久启用" : refillHint(rates, 3), paperPermanent);
        g.drawString(font, "非消耗品", left, y + 237, GOLD);
        slot(g, left, y + 252, inner, 5, state.compassInstalled() ? "已装入 · 点击取出" : "点击装入", false);
        if (!materialNotice.isBlank())
            g.drawString(font, shorten(materialNotice, inner), left, y + 295,
                    materialNoticeError ? 0xffffa789 : CREAM, false);
        boolean canExpand = minecraft.player.getAbilities().instabuild ||
                state.paperEnabled() && (rates.permanent(3) || state.paperUnits() >= rates.cost(3));
        button(g, left, y + h - 31, 177, 22,
                "使用纸值扩充地图册", false,
                canExpand ? () -> {
                    materialNotice = "正在扩充地图册，等待服务端确认…";
                    materialNoticeError = false;
                    materialPending = true;
                    requestStartedAtTick = ticks;
                    CartographClient.sendAddPage();
                } : () -> {
                    materialNotice = "地图纸值不足，先投入上面的空地图";
                    materialNoticeError = true;
                });
        button(g, x + w - 85, y + h - 31, 71, 22, "完成", true, this::closeDialog);
    }

    private String refillAmount(MapNetwork.MaterialRates rates, int material) {
        return String.format(Locale.ROOT, "%.1f", CartographConfig.refillUnits(material, rates.input(material)) / 10.0);
    }

    private String refillHint(MapNetwork.MaterialRates rates, int material) {
        return "消耗 1 个 · 补充 " + refillAmount(rates, material);
    }

    private void slot(GuiGraphics g, int x, int y, int width, int index, String detail, boolean disabled) {
        rounded(g, x, y, width, 39, 5, disabled ? INSET : inside(mouseX, mouseY, x, y, width, 39) ? HOVER : CONTROL);
        rounded(g, x + 5, y + 6, 27, 27, 4, INSET);
        g.renderItem(new ItemStack(MATERIALS[index]), x + 10, y + 11);
        g.drawString(font, shorten(MATERIAL_NAMES[index], width - 39), x + 37, y + 8, CREAM);
        g.drawString(font, shorten(detail, width - 39), x + 37, y + 23, MUTED);
        if (!disabled) hits.add(new Hit(x, y, width, 39, () -> requestRefill(index)));
    }

    private void openMarker(AtlasMarker marker, int x, int z) {
        annotationPending = false;
        clearFields(); editingMarker = marker;
        markerIcon = marker == null ? 0 : marker.icon();
        markerGlow = marker != null && marker.glowing();
        dialog = Dialog.MARKER; menu = Menu.NONE; selection = null; panel = Panel.NONE;
        markerFields(marker == null ? "" : marker.name(), Integer.toString(x), Integer.toString(z),
                hex(marker == null ? COLORS[0] : marker.color()));
    }

    private void markerFields(String title, String xValue, String zValue, String color) {
        int x = modalX() + 14, y = modalY(), inner = modalW() - 28, half = (inner - 8) / 2;
        name = field(x, y + 58, inner, title, AtlasMarker.MAX_NAME, "标点名称");
        setFocused(name); name.setFocused(true);
        coordinateX = field(x, y + 97, half, xValue, 9, "X");
        coordinateZ = field(x + half + 8, y + 97, half, zValue, 9, "Z");
        coordinateX.setFilter(v -> v.matches("-?[0-9]{0,8}"));
        coordinateZ.setFilter(v -> v.matches("-?[0-9]{0,8}"));
        hexColor = field(x + 117, y + 215, inner - 117, color, 7, "颜色");
    }

    private void markerEditor(GuiGraphics g, int x, int y, int w, int h) {
        int left = x + 14, inner = w - 28;
        g.drawString(font, "标点名称", left, y + 45, CREAM);
        g.drawString(font, "X", left, y + 84, CREAM);
        g.drawString(font, "Z", left + inner / 2 + 4, y + 84, CREAM);
        g.drawString(font, "图标 · " + ICON_NAMES[markerIcon], left, y + 122, GOLD);
        int col = (inner - 7 * 4) / 8;
        for (int i = 0; i < ICON_NAMES.length; i++) {
            int icon = i, xx = left + (i % 8) * (col + 4), yy = y + 137 + i / 8 * 28;
            button(g, xx, yy, col, 23, markerGlyph(i), i == markerIcon, () -> markerIcon = icon);
        }
        g.drawString(font, "标点颜色", left, y + 201, GOLD);
        swatches(g, left, y + 217, COLORS);
        check(g, left, y + 241, inner - 168, "发光标点", markerGlow, () -> markerGlow = !markerGlow);
        button(g, x + w - 162, y + h - 30, 69, 22, "取消", false, this::closeDialog);
        button(g, x + w - 85, y + h - 30, 71, 22, "保存", true, this::saveMarker);
    }

    private void swatches(GuiGraphics g, int x, int y, int[] colors) {
        for (int i = 0; i < colors.length; i++) {
            int xx = x + 16 * i, rgb = colors[i];
            rounded(g, xx, y, 14, 14, 3, 0xff000000 | rgb);
            if (hexColor != null && hexColor.getValue().equalsIgnoreCase(hex(rgb)))
                g.renderOutline(xx - 1, y - 1, 16, 16, GOLD);
            hits.add(new Hit(xx, y, 14, 14, () -> {
                if (hexColor != null) hexColor.setValue(hex(rgb));
            }));
        }
    }

    private void saveMarker() {
        if (annotationPending) return;
        String title = name == null ? "" : name.getValue().strip();
        if (title.isEmpty()) { toast("请输入标点名称", true); return; }
        try {
            int x = Integer.parseInt(coordinateX.getValue()), z = Integer.parseInt(coordinateZ.getValue());
            if (Math.abs((long) x) > WORLD_LIMIT || Math.abs((long) z) > WORLD_LIMIT)
                throw new NumberFormatException();
            int color = parseHex(hexColor.getValue());
            CartographClient.sendMarker(new MapNetwork.EditMarker(CartographClient.atlasId(),
                    editingMarker == null ? "" : editingMarker.id(), x, z, title, markerIcon, color, markerGlow, false));
            annotationPending = true;
            requestStartedAtTick = ticks;
            toast("正在保存标点，请确认服务器结果…", false);
        } catch (NumberFormatException bad) { toast("检查 X / Z 坐标和颜色格式", true); }
    }

    private void openRegion(NamedRegion existing, List<Point> points) {
        annotationPending = false;
        clearFields(); selection = null; panel = Panel.NONE; menu = Menu.NONE;
        editingRegion = existing; draft = new ArrayList<>(points); adjustVertices = true;
        regionType = existing == null ? NamedRegion.TYPES.length - 1 : existing.type();
        dialog = Dialog.REGION;
        regionFields(existing == null ? "" : existing.name(), existing == null ? "" : existing.note(),
                hex(existing == null ? REGION_COLORS[regionType] : existing.color()));
    }

    private void regionFields(String title, String description, String color) {
        int x = modalX() + 14, y = modalY(), inner = modalW() - 28;
        name = field(x, y + 58, inner, title, NamedRegion.MAX_NAME, "区域名称");
        setFocused(name); name.setFocused(true);
        hexColor = field(x + 121, y + 133, inner - 121, color, 7, "区域颜色");
        note = field(x, y + 174, inner, description, NamedRegion.MAX_NOTE, "备注");
    }

    private void regionEditor(GuiGraphics g, int x, int y, int w, int h) {
        int left = x + 14, inner = w - 28;
        g.drawString(font, "区域名称", left, y + 45, CREAM);
        g.drawString(font, "区域类型", left, y + 85, GOLD);
        int step = inner / NamedRegion.TYPES.length;
        for (int i = 0; i < NamedRegion.TYPES.length; i++) {
            int index = i;
            button(g, left + i * step, y + 98, step - 3, 22, NamedRegion.TYPES[i], regionType == i, () -> {
                regionType = index; hexColor.setValue(hex(REGION_COLORS[index]));
            });
        }
        g.drawString(font, "颜色", left, y + 128, CREAM);
        swatches(g, left, y + 142, REGION_COLORS);
        g.drawString(font, "备注", left, y + 161, CREAM);
        g.drawString(font, draft.size() + " 个顶点", left, y + 207, MUTED);
        button(g, x + w - 162, y + h - 29, 69, 22, "取消", false, this::closeDialog);
        button(g, x + w - 85, y + h - 29, 71, 22, "保存", true, this::saveRegion);
    }

    private void saveRegion() {
        if (annotationPending) return;
        if (name.getValue().isBlank()) { toast("请输入区域名称", true); return; }
        try {
            CartographClient.sendRegion(new MapNetwork.EditRegion(CartographClient.atlasId(),
                    editingRegion == null ? "" : editingRegion.id(), name.getValue(), regionType,
                    parseHex(hexColor.getValue()), note.getValue(), List.copyOf(draft), false));
            annotationPending = true;
            requestStartedAtTick = ticks;
            toast("正在保存区域，请确认服务器结果…", false);
        } catch (NumberFormatException bad) { toast("颜色使用 #RRGGBB 格式", true); }
    }

    private EditBox field(int x, int y, int width, String value, int length, String label) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal(label));
        box.setMaxLength(length);
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private void resizeFields() {
        if (dialog == Dialog.MARKER && name != null) {
            String n = name.getValue(), x = coordinateX.getValue(), z = coordinateZ.getValue(), c = hexColor.getValue();
            clearFields(); markerFields(n, x, z, c);
        } else if (dialog == Dialog.REGION && name != null) {
            String n = name.getValue(), c = hexColor.getValue(), d = note.getValue();
            clearFields(); regionFields(n, d, c);
        }
    }

    private void clearFields() {
        clearWidgets(); name = coordinateX = coordinateZ = hexColor = note = null;
    }

    private void confirm(String text, Runnable action) {
        clearFields();
        confirmation = text; confirmed = action; dialog = Dialog.CONFIRM; selection = null;
    }

    private void closeDialog() {
        annotationPending = false;
        materialPending = false;
        if (dialog == Dialog.SETTINGS) {
            if (ratesDirty && CartographClient.settings().editable()) {
                CartographClient.sendSettings(new MapNetwork.MaterialRates(costs[0], costs[1], costs[2], costs[3],
                        inputs[0], inputs[1], inputs[2], inputs[3], permanentFlags, newSheetSideBlocks));
            }
            if (performanceDirty && CartographClient.performance().editable())
                CartographClient.sendPerformance(preferredWorkers, preferredBudgetMs);
            cfg.save();
        }
        if (dialog == Dialog.REGION) discardDraft();
        confirmed = null; dialog = Dialog.NONE; clearFields();
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String value, boolean active, Runnable action) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        rounded(g, x, y, w, h, 5, active ? GOLD : hover ? HOVER : CONTROL);
        String label = shorten(value, w - 6);
        // No Minecraft text shadow: on gold/selected buttons it produces
        // a doubled, blurry "bold" effect at common GUI scales.
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2,
                active ? INSET : CREAM, false);
        if (action != null) hits.add(new Hit(x, y, w, h, action));
    }

    private void check(GuiGraphics g, int x, int y, int w, String label, boolean selected, Runnable action) {
        int color = action == null ? MUTED : CREAM;
        if (action != null && inside(mouseX, mouseY, x, y, w, 20)) rounded(g, x, y, w, 20, 4, HOVER);
        rounded(g, x + 4, y + 5, 11, 11, 3, selected ? GOLD : OUTLINE);
        if (selected) g.drawCenteredString(font, "✓", x + 9, y + 6, INSET);
        g.drawString(font, shorten(label, w - 22), x + 20, y + 6, color);
        if (action != null) hits.add(new Hit(x, y, w, 20, action));
    }

    private void textRow(GuiGraphics g, int x, int y, int width, String label, String value) {
        String shown = shorten(value, width * 2 / 3);
        int size = font.width(shown);
        g.drawString(font, shorten(label, width - size - 5), x, y, MUTED);
        g.drawString(font, shown, x + width - size, y, CREAM);
    }

    private String shorten(String value, int max) {
        if (max <= 3) return "";
        return font.width(value) <= max ? value : font.plainSubstrByWidth(value, Math.max(0, max - font.width("…"))) + "…";
    }

    private static boolean inside(double x, double y, int left, int top, int w, int h) {
        return x >= left && x < left + w && y >= top && y < top + h;
    }

    private static String hex(int color) { return String.format(Locale.ROOT, "#%06x", color); }
    private static int parseHex(String text) {
        String v = text.strip().replaceFirst("^#", "");
        if (!v.matches("[0-9A-Fa-f]{6}")) throw new NumberFormatException();
        return Integer.parseInt(v, 16);
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        mouseX = (int) ux(mx); mouseY = (int) ux(my);
        if (dialog != Dialog.NONE && super.mouseClicked(ux(mx), ux(my), button)) return true;
        if (button == 0) for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(ux(mx), ux(my))) { hit.action().run(); return true; }
        }
        if (dialog != Dialog.NONE) return true;
        if (button == 1 && hits.stream().anyMatch(hit -> hit.contains(ux(mx), ux(my)))) return true;
        if (menu != Menu.NONE) { menu = Menu.NONE; return true; }
        if (draft != null) return drawingClick(mx, my, button);
        if (button == 1) {
            selection = null;
            contextX = mouseX; contextY = mouseY; contextWorldX = worldX(mx); contextWorldZ = worldZ(my);
            menu = Menu.MAP; return true;
        }
        if (button == 0) {
            boolean hadSelection = selection != null;
            selection = null;
            startPan(mx, my);
            suppressSelection = hadSelection;
            return true;
        }
        return false;
    }

    private boolean drawingClick(double mx, double my, int button) {
        Point p = new Point(worldX(mx), worldZ(my));
        int vertex = nearestVertex(mx, my);
        if (button == 1) {
            if (adjustVertices && vertex >= 0) {
                menuVertex = vertex; menu = Menu.VERTEX; contextX = mouseX; contextY = mouseY;
            } else if (draft.size() >= 3) adjustVertices = true;
            return true;
        }
        if (button != 0) return true;
        if (!adjustVertices) {
            if (draft.size() >= 3 && vertex == 0) adjustVertices = true;
            else if (draft.size() < NamedRegion.MAX_VERTICES) draft.add(p);
            else toast("最多 64 个边界点", true);
        } else if (vertex >= 0) grabbedVertex = vertex;
        else {
            int edge = nearestEdge(mx, my);
            long now = System.nanoTime();
            if (edge >= 0 && edge == clickedEdge && now - edgeClickNanos < 400_000_000L
                    && draft.size() < NamedRegion.MAX_VERTICES) {
                draft.add(edge + 1, p); clickedEdge = -1;
            } else {
                clickedEdge = edge; edgeClickNanos = now; startPan(mx, my);
            }
        }
        return true;
    }

    private int nearestVertex(double x, double y) {
        double best = 9; int index = -1;
        for (int i = 0; i < draft.size(); i++) {
            Point p = draft.get(i);
            double d = Math.hypot(screenX(p.x()) - x, screenZ(p.z()) - y);
            if (d < best) { index = i; best = d; }
        }
        return index;
    }

    private int nearestEdge(double x, double y) {
        double best = 9; int index = -1;
        for (int i = 0; i < draft.size(); i++) {
            Point a = draft.get(i), b = draft.get((i + 1) % draft.size());
            double x0 = screenX(a.x()), y0 = screenZ(a.z());
            double dx = screenX(b.x()) - x0, dy = screenZ(b.z()) - y0;
            double length = dx * dx + dy * dy;
            if (length == 0) continue;
            double t = Math.clamp(((x - x0) * dx + (y - y0) * dy) / length, 0, 1);
            double d = Math.hypot(x - x0 - t * dx, y - y0 - t * dy);
            if (d < best) { best = d; index = i; }
        }
        return index;
    }

    private void startPan(double mx, double my) {
        panning = true; moved = false; suppressSelection = false;
        pressX = mx; pressY = my; dragX = viewX; dragZ = viewZ;
    }

    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button != 0) return false;
        if (activeSlider != null) { activeSlider.update(ux(mx)); return true; }
        if (dialog != Dialog.NONE) return super.mouseDragged(ux(mx), ux(my), button, ux(dx), ux(dy));
        if (movingSplit) { split = Math.clamp(mx / width, .06, .94); return true; }
        if (draft != null && grabbedVertex >= 0) {
            draft.set(grabbedVertex, new Point(worldX(mx), worldZ(my))); return true;
        }
        if (!panning) return false;
        if (!moved && Math.hypot(mx - pressX, my - pressY) < 3 / minecraft.getWindow().getGuiScale()) return true;
        moved = true; manuallyMoved = true; selection = null;
        viewX = Math.clamp(dragX - (mx - pressX) / zoom, -WORLD_LIMIT, WORLD_LIMIT);
        viewZ = Math.clamp(dragZ - (my - pressY) / zoom, -WORLD_LIMIT, WORLD_LIMIT);
        return true;
    }

    @Override public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            if (activeSlider != null && settingsTab == SettingsTab.MINI) cfg.save();
            activeSlider = null; movingSplit = false; grabbedVertex = -1;
            if (panning) {
                panning = false;
                if (moved) {
                    CartographClient.prioritizeViewport();
                    reload(false, false);
                }
                else if (!suppressSelection && draft == null && dialog == Dialog.NONE) select(worldX(mx), worldZ(my), true);
                return true;
            }
        }
        return super.mouseReleased(ux(mx), ux(my), button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (dialog == Dialog.SETTINGS) {
            settingsScroll = Math.clamp(settingsScroll - (int) Math.round(dy * 24), 0, maxSettingsScroll());
            return true;
        }
        if (dialog != Dialog.NONE) return true;
        int panelX = w() - Math.min(228, w() - 28) - 14;
        int panelHeight = Math.min(h() - 122, panel == Panel.ATLAS ? 231 : panel == Panel.INFO ? 184 : 276);
        if (panel != Panel.NONE && inside(ux(mx), ux(my), panelX, 62, w() - panelX - 14, panelHeight)) {
            if (panel == Panel.REGIONS || panel == Panel.MARKERS) {
                int count = panel == Panel.REGIONS ? CartographClient.regions().size() : markers().size();
                int rows = Math.max(0, (panelHeight - 68) / 26);
                scroll = Math.clamp(scroll - (int) Math.signum(dy), 0, Math.max(0, count - rows));
            }
            return true;
        }
        if (dy == 0) return false;
        selection = null; menu = Menu.NONE; manuallyMoved = true;
        double x = viewX + (mx - width * .5) / zoom, z = viewZ + (my - height * .5) / zoom;
        zoomPixels = Math.clamp(zoomPixels * Math.pow(1.25, Math.clamp(dy, -4, 4)), .20, 20.0);
        zoom = zoomPixels / minecraft.getWindow().getGuiScale();
        viewX = Math.clamp(x - (mx - width * .5) / zoom, -WORLD_LIMIT, WORLD_LIMIT);
        viewZ = Math.clamp(z - (my - height * .5) / zoom, -WORLD_LIMIT, WORLD_LIMIT);
        CartographClient.prioritizeViewport();
        reload(false, false); return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (dialog != Dialog.NONE) { closeDialog(); return true; }
            if (menu != Menu.NONE) { menu = Menu.NONE; return true; }
            if (draft != null) { discardDraft(); return true; }
            if (selection != null) { selection = null; return true; }
            if (panel != Panel.NONE) { panel = Panel.NONE; return true; }
        }
        if (key == GLFW.GLFW_KEY_HOME && dialog == Dialog.NONE) {
            recenter(); return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER && draft != null && dialog == Dialog.NONE) { finishDraft(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float partial) { }
    @Override public boolean isPauseScreen() { return false; }
}
