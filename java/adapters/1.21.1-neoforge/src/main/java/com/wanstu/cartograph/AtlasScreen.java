package com.wanstu.cartograph;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

/**
 * Integrated atlas: continuous multi-page pan/zoom, verified server tiles,
 * two material groups and editable named, colored, glowing waypoints.
 */
public final class AtlasScreen extends Screen {
    private static final int[] COLORS = {0xf7f2df, 0xe99357, 0xeeca53, 0x70b67b, 0x72a7d1, 0xcf89c0};
    private static final String[] SYMBOLS = {"⌂", "⚑", "◆", "✦", "●", "◎"};
    private static final String[] SYMBOL_NAMES = {"住处", "旗帜", "矿区", "地标", "港口", "其他"};

    private double viewX, viewZ, zoom = 2.25;
    private boolean first = true;
    private int frameTicks, actionRefreshTicks;
    private int menuX, menuY, pendingX, pendingZ;
    private AtlasMarker selected;
    private Mode mode = Mode.NONE;
    private EditBox nameInput;
    private int editIcon, editColor;
    private boolean editGlow;

    private enum Mode { NONE, ADD_MENU, MARKER_MENU, MATERIAL_MENU, EDIT, SETTINGS }
    private int[] newRates = {10, 10, 10};

    public AtlasScreen() {
        super(Component.translatable("screen.cartograph.atlas"));
    }

    @Override
    protected void init() {
        super.init();
        if (first && minecraft != null && minecraft.player != null) {
            viewX = minecraft.player.getBlockX();
            viewZ = minecraft.player.getBlockZ();
            first = false;
        }
        if (mode == Mode.EDIT) startEditor();
        if (mode == Mode.SETTINGS) startSettings();
        reloadVisible(false);
    }

    private int panelWidth() { return width < 570 ? 140 : 196; }
    private int left() { return 16; }
    private int right() { return width - panelWidth() - 18; }
    private int top() { return 40; }
    private int bottom() { return height - 42; }
    private int mapMidX() { return (left() + right()) / 2; }
    private int mapMidY() { return (top() + bottom()) / 2; }
    private int side() { return right() + 12; }
    private boolean insideMap(double x, double y) {
        return x >= left() && x < right() && y >= top() && y < bottom();
    }

    private int worldX(double mouseX) {
        return (int) Math.floor(viewX + (mouseX - mapMidX()) / zoom);
    }
    private int worldZ(double mouseY) {
        return (int) Math.floor(viewZ + (mouseY - mapMidY()) / zoom);
    }
    private int mapX(double world) {
        return (int) Math.round(mapMidX() + (world - viewX) * zoom);
    }
    private int mapY(double world) {
        return (int) Math.round(mapMidY() + (world - viewZ) * zoom);
    }

    private int firstPageX() { return Math.floorDiv(worldX(left()), 128); }
    private int lastPageX() { return Math.floorDiv(worldX(right()), 128); }
    private int firstPageZ() { return Math.floorDiv(worldZ(top()), 128); }
    private int lastPageZ() { return Math.floorDiv(worldZ(bottom()), 128); }

    private void reloadVisible(boolean force) {
        // No more than 64 tile requests in an extreme UI scale.
        int count = 0;
        int cx = Math.floorDiv((int) viewX, 128);
        int cz = Math.floorDiv((int) viewZ, 128);
        for (int ring = 0; ring <= 9 && count < 64; ring++) {
            for (int z = Math.max(firstPageZ(), cz - ring); z <= Math.min(lastPageZ(), cz + ring); z++) {
                for (int x = Math.max(firstPageX(), cx - ring); x <= Math.min(lastPageX(), cx + ring); x++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == ring) {
                        CartographClient.queue(x, z, force);
                        count++;
                        if (count >= 64) break;
                    }
                }
                if (count >= 64) break;
            }
        }
    }

    @Override
    public void tick() {
        if (++frameTicks % 200 == 0) reloadVisible(true);
        if (actionRefreshTicks > 0 && --actionRefreshTicks == 0) reloadVisible(true);
        // EditBox cursor and keyboard events are managed by the screen.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0xff142019);
        g.drawCenteredString(font, title, width / 2, 13, 0xffe8d0a0);
        drawMap(g);
        drawSidebar(g);
        drawContext(g, mouseX, mouseY);
        if (mode == Mode.EDIT) drawEditor(g);
        if (mode == Mode.SETTINGS) drawSettings(g);
        super.render(g, mouseX, mouseY, partial);
    }

    /**
     * This screen paints its own opaque full-screen background in render().
     * Vanilla Screen.render() invokes renderBackground() AFTER our custom
     * map/widgets rendering, and its post-process blur would consequently
     * blur the entire atlas UI (text, buttons, map and input controls).
     * Suppress only this redundant background pass; super.render() still
     * renders Minecraft's actual interactive widgets above our graphics.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally no-op: render() already draws a full-screen background.
    }

    private void drawMap(GuiGraphics g) {
        int l = left(), t = top(), r = right(), b = bottom();
        g.fill(l - 4, t - 4, r + 4, b + 4, 0xffb49b6f);
        g.fill(l, t, r, b, 0xffe1d2b0);
        g.enableScissor(l, t, r, b);
        int visible = 0;
        for (int z = firstPageZ(); z <= lastPageZ() && visible < 100; z++) {
            for (int x = firstPageX(); x <= lastPageX() && visible < 100; x++) {
                visible++;
                ResourceLocation tex = CartographClient.texture(x, z);
                MapNetwork.Page page = CartographClient.page(x, z);
                int sx = mapX((long) x * 128), sy = mapY((long) z * 128);
                int ex = mapX(((long) x + 1) * 128), ey = mapY(((long) z + 1) * 128);
                if (tex != null && page != null && page.inRange()) {
                    g.pose().pushPose();
                    g.pose().translate(sx, sy, 0);
                    g.pose().scale((float) zoom, (float) zoom, 1f);
                    g.blit(tex, 0, 0, 0, 0, 128, 128, 128, 128);
                    g.pose().popPose();
                    drawMarkers(g, page);
                } else {
                    g.fill(sx, sy, ex, ey, 0xffdbceae);
                }
                // Crisp page grid: visible but does not mask the surveyed terrain.
                g.fill(sx, sy, ex, sy + 1, 0x66725d43);
                g.fill(sx, sy, sx + 1, ey, 0x66725d43);
            }
        }
        // Player position is gated by the compass installed on that atlas.
        int px = minecraft.player == null ? 0 : minecraft.player.getBlockX();
        int pz = minecraft.player == null ? 0 : minecraft.player.getBlockZ();
        MapNetwork.Page playerPage = CartographClient.page(Math.floorDiv(px,128), Math.floorDiv(pz,128));
        if (playerPage != null && playerPage.compass()) {
            int sx = mapX(px), sy = mapY(pz);
            g.fill(sx - 4, sy - 4, sx + 5, sy + 5, 0xffe24b42);
            g.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xfffcefbf);
        }
        g.disableScissor();
        g.drawString(font, "X " + (int) viewX + "   Z " + (int) viewZ
                + "   " + (int) (zoom * 100) + "%", l + 3, b + 10, 0xffc4bd9f);
        g.drawString(font, "拖动平移 · 滚轮缩放 · 右键标点 · M打开",
                l + 3, b + 23, 0xffb6b99e);
    }

    private void drawMarkers(GuiGraphics g, MapNetwork.Page page) {
        // Automatic symbols require a verified placed surface footprint on a
        // FULL generated chunk, not simply a structure-reference lookup.
        for (SurfaceStructure structure : page.structures()) {
            int sx = mapX(structure.x()), sy = mapY(structure.z());
            if (!insideMap(sx, sy)) continue;
            g.fill(sx - 5, sy - 5, sx + 6, sy + 6, 0xff444f40);
            g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xffffd788);
            g.drawString(font, "S", sx - 3, sy - 4, 0xff293522, false);
            if (zoom >= 2.0) g.drawString(font, structureTitle(structure.type()),
                    sx + 8, sy - 5, 0xff413527, false);
        }
        for (AtlasMarker marker : page.markers()) {
            int sx = mapX(marker.x()), sy = mapY(marker.z());
            if (!insideMap(sx, sy)) continue;
            int color = COLORS[Math.clamp(marker.color(), 0, COLORS.length - 1)];
            if (marker.glowing()) {
                g.fill(sx - 6, sy - 6, sx + 7, sy + 7, 0x99fff0a4);
                g.fill(sx - 4, sy - 4, sx + 5, sy + 5, 0xfff3d683);
            }
            drawIcon(g, sx, sy, marker.icon(), color);
            if (zoom >= 2.0) {
                g.drawString(font, marker.name(), sx + 7, sy - 5, 0xff333026, false);
            }
        }
    }

    /** Small distinct pictograms that render without an optional icon-font resource. */
    private void drawIcon(GuiGraphics g, int x, int y, int icon, int color) {
        int light = 0xff000000 | color;
        int dark = 0xff252924;
        g.fill(x - 5, y - 5, x + 6, y + 6, dark);
        g.fill(x - 4, y - 4, x + 5, y + 5, 0xffede1bd);
        switch (icon) {
            case 0 -> { // house
                g.fill(x - 4, y - 1, x + 5, y, light);
                g.fill(x - 3, y - 2, x + 4, y - 1, light);
                g.fill(x - 2, y - 3, x + 3, y - 2, light);
                g.fill(x - 3, y, x + 4, y + 4, light);
                g.fill(x, y + 1, x + 2, y + 4, dark);
            }
            case 1 -> { // flag
                g.fill(x - 3, y - 4, x - 2, y + 4, dark);
                g.fill(x - 2, y - 4, x + 4, y - 1, light);
                g.fill(x - 2, y - 1, x + 2, y + 1, light);
            }
            case 2 -> { // crossing mining tools
                for (int d = -3; d <= 3; d++) {
                    g.fill(x + d, y + d, x + d + 1, y + d + 1, light);
                    g.fill(x - d, y + d, x - d + 1, y + d + 1, dark);
                }
            }
            case 3 -> { // diamond landmark
                for (int d = 0; d <= 4; d++) {
                    g.fill(x - (4 - d), y - d, x + (4 - d) + 1, y - d + 1, light);
                    g.fill(x - (4 - d), y + d, x + (4 - d) + 1, y + d + 1, light);
                }
            }
            case 4 -> { // harbor / anchor
                g.fill(x - 1, y - 3, x + 2, y + 3, light);
                g.fill(x - 4, y + 1, x - 3, y + 3, light);
                g.fill(x + 3, y + 1, x + 4, y + 3, light);
                g.fill(x - 3, y + 3, x + 4, y + 4, light);
            }
            default -> { // location ring
                g.fill(x - 3, y - 2, x + 4, y + 3, light);
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
            case "minecraft:shipwreck_beached" -> "海滩沉船";
            default -> type.startsWith("minecraft:ruined_portal") ? "废弃传送门" : "地表建筑";
        };
    }

    private void drawSidebar(GuiGraphics g) {
        int x = side(), w = width - x - 7;
        g.fill(x - 4, top() - 4, width - 3, bottom() + 4, 0xff29362e);
        g.drawString(font, "地图册", x + 4, top() + 4, 0xffeed6a8);
        MapNetwork.Page info = CartographClient.page(Math.floorDiv((int) viewX, 128),
                Math.floorDiv((int) viewZ, 128));
        AtlasState mirror = CartographClient.currentAtlas().getOrDefault(
                CartographMod.ATLAS_STATE.get(), AtlasState.blank());
        int ink = info == null ? mirror.inkUnits() : info.inkUnits();
        int pigment = info == null ? mirror.pigmentUnits() : info.pigmentUnits();
        int glow = info == null ? mirror.glowUnits() : info.glowUnits();
        g.drawString(font, "墨水 " + ink / 10.0, x + 4, top() + 24, 0xffe8e9de);
        g.drawString(font, "颜料 " + pigment / 10.0, x + 4, top() + 38, 0xffe8e9de);
        g.drawString(font, "发光墨水 " + glow / 10.0, x + 4, top() + 52, 0xffe8e9de);

        int y = top() + 76;
        g.drawString(font, "耗材填入口 · 通用", x + 4, y - 12, 0xffd4c8aa);
        button(g, x + 4, y, w - 8, 26, "＋ 放入耗材");
        int compassY = y + 48;
        g.drawString(font, "非耗材填入口", x + 4, compassY - 12, 0xffd4c8aa);
        button(g, x + 4, compassY, w - 8, 22,
                (info == null ? mirror.compassInstalled() : info.compass())
                        ? "指南针 ✓ (取出)" : "指南针 + (装入)");

        int controlsY = compassY + 36;
        g.drawString(font, "标点：地图右键", x + 4, controlsY, 0xffc8c7aa);
        g.drawString(font, "右键已有标点可编辑", x + 4, controlsY + 16, 0xffc8c7aa);
        button(g, x + 4, controlsY + 38, w - 8, 20, "消耗倍率设置");
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label) {
        g.fill(x, y, x + w, y + h, 0xff435649);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xff34463b);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, 0xffe9d9b2);
    }

    private int editorX() { return Math.max(6, (width - 244) / 2); }
    private int editorY() { return Math.max(8, (height - 220) / 2); }
    private void drawEditor(GuiGraphics g) {
        int x = editorX(), y = editorY();
        g.fill(x - 4, y - 4, x + 248, y + 220, 0xff15251e);
        g.fill(x, y, x + 244, y + 216, 0xff2f4438);
        g.drawString(font, selected == null ? "新增标点" : "编辑标点", x + 12, y + 12, 0xfff3dfb5);
        g.drawString(font, "名称", x + 12, y + 30, 0xffcbd6bf);
        button(g, x + 12, y + 68, 220, 21, "图标: " + SYMBOL_NAMES[editIcon]);
        button(g, x + 12, y + 93, 220, 21,
                "颜色: " + (canColor() ? ("编号 " + (editColor + 1)) : "需要三原色"));
        g.fill(x + 15, y + 119, x + 229, y + 126, 0xff000000 | COLORS[editColor]);
        button(g, x + 12, y + 133, 220, 21, "发光: " + (editGlow ? "开" : "关"));
        button(g, x + 12, y + 161, 106, 24, "保存标点");
        button(g, x + 126, y + 161, 106, 24, "取消");
        g.drawString(font, "标点限已测绘位置 · 发光首次消耗 1", x + 12, y + 195, 0xffc1b99d);
    }

    private final class RateSlider extends AbstractSliderButton {
        private final int kind;
        RateSlider(int x, int y, int w, int h, int kind) {
            super(x, y, w, h, Component.empty(), newRates[kind] / 100.0);
            this.kind = kind;
            updateMessage();
        }
        @Override protected void updateMessage() {
            String[] names = {"墨水", "颜料", "发光墨水"};
            setMessage(Component.literal(names[kind] + "   " + (Math.round(value * 100) / 10.0) + "×"));
        }
        @Override protected void applyValue() {
            newRates[kind] = Math.clamp((int) Math.round(value * 100), 0, 100);
            updateMessage();
        }
    }

    private void startSettings() {
        mode = Mode.SETTINGS;
        clearWidgets();
        nameInput = null;
        MapNetwork.CostSettings server = CartographClient.settings();
        newRates = new int[]{server.ink(), server.pigment(), server.glow()};
        int x = editorX() + 12, y = editorY() + 48;
        for (int i = 0; i < 3; i++) {
            RateSlider slider = new RateSlider(x, y + i * 37, 220, 20, i);
            slider.active = server.editable();
            addRenderableWidget(slider);
        }
    }

    private void drawSettings(GuiGraphics g) {
        int x = editorX(), y = editorY();
        g.fill(x - 4, y - 4, x + 248, y + 220, 0xff15251e);
        g.fill(x, y, x + 244, y + 216, 0xff2f4438);
        g.drawString(font, "模组设置 · 资源消耗倍率", x + 12, y + 12, 0xfff3dfb5);
        g.drawString(font, CartographClient.settings().editable()
                        ? "0～10 倍，0 代表不消耗；服务端保存"
                        : "仅管理员 / 单人游戏房主可修改",
                x + 12, y + 30, 0xffcbd6bf);
        button(g, x + 12, y + 167, 106, 24, "保存设置");
        button(g, x + 126, y + 167, 106, 24, "取消");
        g.drawString(font, "首次材料解锁仍是必要条件", x + 12, y + 200, 0xffc1b99d);
    }

    private void closeSettings(boolean save) {
        if (save && CartographClient.settings().editable()) {
            CartographClient.sendSettings(newRates[0], newRates[1], newRates[2]);
        }
        clearWidgets();
        mode = Mode.NONE;
    }

    private boolean canColor() {
        return CartographClient.currentAtlas().getOrDefault(
                CartographMod.ATLAS_STATE.get(), AtlasState.blank()).colorEnabled();
    }

    private void drawContext(GuiGraphics g, int mouseX, int mouseY) {
        if (mode != Mode.ADD_MENU && mode != Mode.MARKER_MENU && mode != Mode.MATERIAL_MENU) return;
        int x = Math.max(4, Math.min(menuX, width - 150));
        int y = Math.max(4, Math.min(menuY, height - (mode == Mode.MATERIAL_MENU ? 132 : 70)));
        g.fill(x, y, x + 136, y + (mode == Mode.MATERIAL_MENU ? 124 : 49), 0xff182b23);
        if (mode == Mode.MATERIAL_MENU) {
            String[] opts = {"墨囊", "发光墨囊", "红色染料", "黄色染料", "蓝色染料"};
            for (int i = 0; i < 5; i++) button(g, x + 4, y + 4 + i * 23, 128, 21, opts[i]);
        } else if (mode == Mode.ADD_MENU) {
            button(g, x + 4, y + 4, 128, 19, "新增标点");
            button(g, x + 4, y + 26, 128, 19, "取出地图 (空地图)");
        } else {
            button(g, x + 4, y + 4, 128, 19, "编辑标点");
            button(g, x + 4, y + 26, 128, 19, "删除标点");
        }
    }

    private AtlasMarker markerNear(double mx, double my) {
        for (int pz = firstPageZ(); pz <= lastPageZ(); pz++) {
            for (int px = firstPageX(); px <= lastPageX(); px++) {
                MapNetwork.Page page = CartographClient.page(px, pz);
                if (page == null || !page.inRange()) continue;
                for (AtlasMarker marker : page.markers()) {
                    if (Math.abs(mx - mapX(marker.x())) <= 7
                            && Math.abs(my - mapY(marker.z())) <= 7)
                        return marker;
                }
            }
        }
        return null;
    }

    private void startEditor() {
        mode = Mode.EDIT;
        clearWidgets();
        int x = editorX() + 12, y = editorY() + 43;
        nameInput = new EditBox(font, x, y, 220, 20, Component.literal("标点名称"));
        nameInput.setMaxLength(24);
        nameInput.setValue(selected == null ? "标点" : selected.name());
        addRenderableWidget(nameInput);
        setInitialFocus(nameInput);
        editIcon = selected == null ? 0 : selected.icon();
        editColor = selected == null ? 0 : selected.color();
        editGlow = selected != null && selected.glowing();
    }

    private void closeEditor() {
        nameInput = null;
        clearWidgets();
        mode = Mode.NONE;
        selected = null;
    }

    private void saveEditor() {
        if (nameInput == null) return;
        CartographClient.sendMarker(new MapNetwork.EditMarker(CartographClient.atlasId(),
                selected == null ? "" : selected.id(), pendingX, pendingZ,
                nameInput.getValue(), editIcon, editColor, editGlow, false));
        actionRefreshTicks = 55;
        closeEditor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mode == Mode.SETTINGS) {
            if (super.mouseClicked(mx, my, button)) return true;
            int x = editorX(), y = editorY();
            if (button == 0 && my >= y + 167 && my < y + 191) {
                if (mx >= x + 12 && mx < x + 118) closeSettings(true);
                else if (mx >= x + 126 && mx < x + 232) closeSettings(false);
            }
            return true;
        }
        if (mode == Mode.EDIT) {
            if (super.mouseClicked(mx, my, button)) return true;
            int x = editorX(), y = editorY();
            if (button == 0 && mx >= x + 12 && mx < x + 232) {
                if (my >= y + 68 && my < y + 89) editIcon = (editIcon + 1) % SYMBOL_NAMES.length;
                else if (my >= y + 93 && my < y + 114 && canColor())
                    editColor = (editColor + 1) % COLORS.length;
                else if (my >= y + 133 && my < y + 154) editGlow = !editGlow;
                else if (my >= y + 161 && my < y + 185) {
                    if (mx < x + 122) saveEditor();
                    else closeEditor();
                }
                return true;
            }
            return true;
        }

        if (mode == Mode.MATERIAL_MENU) {
            int x = Math.max(4, Math.min(menuX, width - 150));
            int y = Math.max(4, Math.min(menuY, height - 132));
            if (button == 0 && mx >= x + 4 && mx <= x + 132
                    && my >= y + 4 && my < y + 4 + 5 * 23) {
                int material = (int) ((my - y - 4) / 23);
                CartographClient.sendRefill(Math.clamp(material, 0, 4));
                actionRefreshTicks = 55;
            }
            mode = Mode.NONE;
            return true;
        }

        if (mode == Mode.ADD_MENU || mode == Mode.MARKER_MENU) {
            int x = Math.max(4, Math.min(menuX, width - 150));
            int y = Math.max(4, Math.min(menuY, height - 70));
            if (button == 0 && mx >= x && mx <= x + 136 && my >= y && my <= y + 49) {
                if (my < y + 25) {
                    startEditor();
                } else if (mode == Mode.MARKER_MENU && selected != null) {
                    CartographClient.sendMarker(new MapNetwork.EditMarker(CartographClient.atlasId(),
                            selected.id(), selected.x(), selected.z(), selected.name(),
                            selected.icon(), selected.color(), selected.glowing(), true));
                    actionRefreshTicks = 55;
                    mode = Mode.NONE;
                    selected = null;
                } else {
                    // Map export is handled by a dedicated secure packet.
                    CartographClient.exportAt(pendingX, pendingZ);
                    mode = Mode.NONE;
                }
                return true;
            }
            mode = Mode.NONE;
        }

        if (button == 0 && mx >= side() + 4 && mx < width - 7) {
            int y = top() + 76;
            int compassY = y + 48;
            int settingsY = compassY + 36 + 38;
            if (my >= settingsY && my < settingsY + 20) {
                startSettings();
                return true;
            }
            if (my >= y && my < y + 26) {
                mode = Mode.MATERIAL_MENU;
                menuX = (int) (side() + 4);
                menuY = (int) (y + 28);
                return true;
            }
            if (my >= compassY && my < compassY + 22) {
                CartographClient.sendRefill(5);
                actionRefreshTicks = 55;
                return true;
            }
        }

        if (button == 1 && insideMap(mx, my)) {
            pendingX = worldX(mx);
            pendingZ = worldZ(my);
            if (Math.abs((long) pendingX) > 30_000_000 || Math.abs((long) pendingZ) > 30_000_000)
                return true;
            selected = markerNear(mx, my);
            if (selected != null) {
                pendingX = selected.x();
                pendingZ = selected.z();
            }
            mode = selected == null ? Mode.ADD_MENU : Mode.MARKER_MENU;
            menuX = (int) mx;
            menuY = (int) my;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && mode == Mode.NONE && insideMap(mx, my)) {
            viewX -= dx / zoom;
            viewZ -= dy / zoom;
            reloadVisible(false);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double deltaX, double deltaY) {
        if (!insideMap(mx, my)) return super.mouseScrolled(mx, my, deltaX, deltaY);
        double wx = viewX + (mx - mapMidX()) / zoom;
        double wz = viewZ + (my - mapMidY()) / zoom;
        zoom = Math.clamp(zoom * (deltaY > 0 ? 1.25 : 0.8), 1.0, 6.0);
        viewX = wx - (mx - mapMidX()) / zoom;
        viewZ = wz - (my - mapMidY()) / zoom;
        reloadVisible(false);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (mode == Mode.EDIT || mode == Mode.SETTINGS) return super.keyPressed(key, scan, mods);
        int delta = 128;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> viewX -= delta;
            case GLFW.GLFW_KEY_RIGHT -> viewX += delta;
            case GLFW.GLFW_KEY_UP -> viewZ -= delta;
            case GLFW.GLFW_KEY_DOWN -> viewZ += delta;
            default -> { return super.keyPressed(key, scan, mods); }
        }
        reloadVisible(false);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
