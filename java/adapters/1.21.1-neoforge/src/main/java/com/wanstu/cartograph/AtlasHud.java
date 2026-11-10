package com.wanstu.cartograph;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

final class AtlasHud {
    private static final int RASTER = 160, MARGIN = 7;
    private static final double DIAGONAL = Math.sqrt(2.0);
    private static DynamicTexture texture;
    private static ResourceLocation textureId;
    private static Frame previous;
    private static long lastFrameTick = -1;
    private record Frame(int x, int z, int rotation, double zoom, boolean round, boolean reduced,
                         ClientMapSettings.Style style, int revision) {}

    record Layout(int x, int y, int size, int travelX, int travelY) {
        boolean inside(double px, double py) {
            return px >= x && px < x + size && py >= y && py < y + size;
        }
    }

    private AtlasHud() {}

    static int margin() { return MARGIN; }

    static Layout layout(int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        ClientMapSettings cfg = ClientMapSettings.get();
        int limit = Math.max(24, Math.min(width, height) - MARGIN * 2 - 10);
        int size = Math.min(limit, Math.max(28, (int) Math.round(cfg.hudSize / mc.getWindow().getGuiScale())));
        int travelX = Math.max(0, width - size - 2 * MARGIN);
        // The coordinate label is inside the map now: no phantom 12px footer.
        int travelY = Math.max(0, height - size - 2 * MARGIN);
        return new Layout(MARGIN + (int) Math.round(cfg.hudX * travelX),
                MARGIN + (int) Math.round(cfg.hudY * travelY), size, travelX, travelY);
    }

    static void draw(GuiGraphics g, boolean editing) {
        drawWithin(g, editing, false, layout(g.guiWidth(), g.guiHeight()));
    }

    /** Actual held-map layout; the editor uses this SAME calculation. */
    static Layout heldLayout(int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        ClientMapSettings cfg = ClientMapSettings.get();
        double guiScale = Math.max(1, mc.getWindow().getGuiScale());
        int limit = Math.max(28, Math.min(width, height) - MARGIN * 2 - 10);
        int size = Math.min(limit, Math.max(24, (int) Math.round(cfg.heldSize / guiScale)));
        int travelX = Math.max(0, width - size - MARGIN * 2);
        int travelY = Math.max(0, height - size - MARGIN * 2);
        return new Layout(MARGIN + (int) Math.round(cfg.heldX * travelX),
                MARGIN + (int) Math.round(cfg.heldY * travelY), size, travelX, travelY);
    }

    static void drawHeld(GuiGraphics g) { drawHeld(g, false); }

    /** The editor and normal gameplay intentionally share exact coordinates,
     * scaling, framing, flags, and pixel projection. No fake minimap preview. */
    static void drawHeld(GuiGraphics g, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Layout area = heldLayout(g.guiWidth(), g.guiHeight());
        int x = area.x(), y = area.y(), size = area.size();
        AtlasChrome.rounded(g, x - 4, y - 4, size + 8, size + 8, 3, 0xff4a3326);
        AtlasChrome.rounded(g, x - 2, y - 2, size + 4, size + 4, 2, 0xffd8bd84);
        drawWithin(g, editing, true, area);
    }

    private static void drawWithin(GuiGraphics g, boolean editing, boolean held, Layout area) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ClientMapSettings cfg = ClientMapSettings.get();
        boolean northUp = held ? cfg.held(ClientMapSettings.NORTH_UP)
                : cfg.hud(ClientMapSettings.NORTH_UP);
        int rotation = northUp ? 0 : Math.round((mc.player.getYRot() + 180) / 2) * 2;
        double hudZoom = held ? cfg.heldZoom : cfg.hudZoom;
        boolean reduced = area.size() * mc.getWindow().getGuiScale() * hudZoom < 240.0;
        Frame next = new Frame(mc.player.getBlockX(), mc.player.getBlockZ(), rotation,
                hudZoom, held ? false : cfg.round,
                reduced, held ? ClientMapSettings.Style.ILLUSTRATED : cfg.style,
                CartographClient.revision());
        if (!next.equals(previous) && (texture == null || lastFrameTick != mc.level.getGameTime() || editing)) {
            update(next);
            lastFrameTick = mc.level.getGameTime();
        }
        if (textureId == null || previous == null) return;

        g.blit(textureId, area.x(), area.y(), area.size(), area.size(), 0, 0, RASTER, RASTER, RASTER, RASTER);
        Frame drawn = previous;
        double angle = Math.toRadians(drawn.rotation()), scale = area.size() * drawn.zoom() / 240.0;
        if ((held ? cfg.held(ClientMapSettings.ICONS) : cfg.hud(ClientMapSettings.ICONS)))
            drawIcons(g, mc, cfg, area, drawn, angle, scale);
        if ((held ? cfg.held(ClientMapSettings.REGION_NAMES) : cfg.hud(ClientMapSettings.REGION_NAMES))
                && cfg.layer(ClientMapSettings.REGIONS)) {
            for (NamedRegion region : CartographClient.regions()) {
                NamedRegion.Point point = region.labelPosition();
                int[] at = project(area, drawn, angle, scale, point.x(), point.z(), 12);
                if (at != null && CartographClient.surveyed(point.x(), point.z()))
                    g.drawCenteredString(mc.font, region.name(), at[0], at[1], AtlasChrome.CREAM);
            }
        }
        if (CartographClient.state().compassInstalled()) {
            double facing = Math.toRadians(mc.player.getYRot() + 180) - angle;
            AtlasChrome.arrow(g, area.x() + area.size() * .5, area.y() + area.size() * .5, facing, .76);
        }
        if ((held ? cfg.held(ClientMapSettings.CARDINALS) : cfg.hud(ClientMapSettings.CARDINALS)))
            drawCardinals(g, mc, area, angle);
        if ((held ? cfg.held(ClientMapSettings.COORDS) : cfg.hud(ClientMapSettings.COORDS))) {
            // Even very small map sizes must never sprout a text bar larger
            // than the map itself. Use two compact rows and keep them bounded.
            int allowed = Math.max(18, area.size() - 8);
            String sx = "X" + mc.player.getBlockX(), sz = "Z" + mc.player.getBlockZ();
            if (mc.font.width(sx) > allowed) sx = Integer.toString(mc.player.getBlockX());
            if (mc.font.width(sz) > allowed) sz = Integer.toString(mc.player.getBlockZ());
            g.enableScissor(area.x() + 1, area.y(), area.x() + area.size() - 1,
                    area.y() + area.size());
            AtlasChrome.rounded(g, area.x() + 1, area.y() + area.size() - 22,
                    Math.min(area.size() - 2, Math.max(mc.font.width(sx), mc.font.width(sz)) + 5),
                    21, 3, AtlasChrome.PANEL);
            g.drawString(mc.font, sx, area.x() + 3, area.y() + area.size() - 20,
                    AtlasChrome.CREAM, false);
            g.drawString(mc.font, sz, area.x() + 3, area.y() + area.size() - 11,
                    AtlasChrome.CREAM, false);
            g.disableScissor();
        }
        if (editing) {
            int x = area.x() + area.size(), y = area.y() + area.size();
            AtlasChrome.rounded(g, x - 9, y - 9, 14, 14, 3, AtlasChrome.GOLD);
            AtlasChrome.line(g, x - 6, y + 2, x + 2, y - 6, AtlasChrome.INSET, 1);
            AtlasChrome.line(g, x - 2, y + 2, x + 2, y - 2, AtlasChrome.INSET, 1);
        }
    }

    private static void drawCardinals(GuiGraphics g, Minecraft mc, Layout area, double rotation) {
        int cx = area.x() + area.size() / 2, cy = area.y() + area.size() / 2;
        int radius = area.size() / 2 - 7;
        String[] names = {"N", "E", "S", "W"};
        for (int i = 0; i < names.length; i++) {
            double a = Math.PI / 2 * i - rotation;
            int x = cx + (int) Math.round(radius * Math.sin(a));
            int y = cy - (int) Math.round(radius * Math.cos(a)) - 4;
            g.drawCenteredString(mc.font, names[i], x, y, 0xfffff0d0);
        }
    }

    private static void drawIcons(GuiGraphics g, Minecraft mc, ClientMapSettings cfg, Layout area, Frame view,
                                  double angle, double scale) {
        Set<String> seen = new HashSet<>();
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            if (cfg.layer(ClientMapSettings.WAYPOINTS)) for (AtlasMarker marker : page.markers()) {
                int[] at = project(area, view, angle, scale, marker.x(), marker.z(), 8);
                if (at == null || !seen.add(marker.id()) || !CartographClient.surveyed(marker.x(), marker.z())) continue;
                AtlasChrome.rounded(g, at[0] - 6, at[1] - 6, 12, 12, view.round() ? 4 : 2, 0xfff1dfb8);
                g.drawCenteredString(mc.font, AtlasScreen.markerGlyph(marker.icon()), at[0], at[1] - 4,
                        0xff000000 | marker.color());
            }
            if (cfg.layer(ClientMapSettings.STRUCTURES)) for (SurfaceStructure point : page.structures()) {
                int[] at = project(area, view, angle, scale, point.x(), point.z(), 7);
                if (at != null) AtlasChrome.rounded(g, at[0] - 3, at[1] - 3, 7, 7, 2, AtlasChrome.GOLD);
            }
        }
    }

    private static int[] project(Layout a, Frame view, double angle, double scale, int x, int z, int margin) {
        double dx = x - view.x(), dz = z - view.z();
        double ux = (dx * Math.cos(angle) + dz * Math.sin(angle)) * scale;
        double uy = (-dx * Math.sin(angle) + dz * Math.cos(angle)) * scale;
        double limit = a.size() * .5 - margin;
        if (view.round() ? ux * ux + uy * uy > limit * limit : Math.abs(ux) > limit || Math.abs(uy) > limit) return null;
        return new int[]{a.x() + a.size() / 2 + (int) Math.round(ux), a.y() + a.size() / 2 + (int) Math.round(uy)};
    }

    private static void update(Frame next) {
        Minecraft mc = Minecraft.getInstance();
        if (texture == null) {
            texture = new DynamicTexture(new NativeImage(RASTER, RASTER, true));
            textureId = mc.getTextureManager().register("cartograph/hud", texture);
        }
        NativeImage out = texture.getPixels();
        if (out == null) return;
        double radius = 120.0 / next.zoom();
        int px0 = Math.floorDiv((int) Math.floor(next.x() - radius * DIAGONAL), 128);
        int pz0 = Math.floorDiv((int) Math.floor(next.z() - radius * DIAGONAL), 128);
        int px1 = Math.floorDiv((int) Math.ceil(next.x() + radius * DIAGONAL), 128);
        int pz1 = Math.floorDiv((int) Math.ceil(next.z() + radius * DIAGONAL), 128);
        int side = px1 - px0 + 1;
        NativeImage[] pages = new NativeImage[side * (pz1 - pz0 + 1)];
        for (int z = pz0; z <= pz1; z++) for (int x = px0; x <= px1; x++)
            pages[(z - pz0) * side + x - px0] = CartographClient.image(x, z);

        double angle = Math.toRadians(next.rotation()), co = Math.cos(angle), si = Math.sin(angle);
        int rim = PixelPageTexture.abgr(0x485641), edge = PixelPageTexture.abgr(0xd5bb85);
        int blank = PixelPageTexture.abgr(PixelPageTexture.PAPER);
        for (int y = 0; y < RASTER; y++) for (int x = 0; x < RASTER; x++) {
            double u = 2 * (x + .5) / RASTER - 1, v = 2 * (y + .5) / RASTER - 1;
            double distance = next.round() ? Math.hypot(u, v) : Math.max(Math.abs(u), Math.abs(v));
            int pixel = 0;
            if (distance <= 1) {
                if (distance >= .975) pixel = rim;
                else if (distance >= .946) pixel = edge;
                else {
                    int wx = (int) Math.floor(next.x() + .5 + radius * (co * u - si * v));
                    int wz = (int) Math.floor(next.z() + .5 + radius * (si * u + co * v));
                    int ix = Math.floorDiv(wx, 128) - px0, iz = Math.floorDiv(wz, 128) - pz0;
                    NativeImage source = ix >= 0 && ix < side && iz >= 0 && iz <= pz1 - pz0 ? pages[iz * side + ix] : null;
                    pixel = source == null ? blank : PixelPageTexture.sample(source, next.style(),
                            Math.floorMod(wx, 128), Math.floorMod(wz, 128), next.reduced());
                }
            }
            out.setPixelRGBA(x, y, pixel);
        }
        texture.upload();
        previous = next;
    }

    static void clear() {
        if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
        texture = null; textureId = null; previous = null; lastFrameTick = -1;
    }
}
