package com.wanstu.cartograph;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

final class AtlasHud {
    private static final int RASTER = 160, MARGIN = 14;
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

    static Layout layout(int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        ClientMapSettings cfg = ClientMapSettings.get();
        int limit = Math.max(24, Math.min(width, height) - MARGIN * 2 - 10);
        int size = Math.min(limit, Math.max(28, (int) Math.round(cfg.hudSize / mc.getWindow().getGuiScale())));
        int travelX = Math.max(0, width - size - 2 * MARGIN);
        int travelY = Math.max(0, height - size - 2 * MARGIN - 12);
        return new Layout(MARGIN + (int) Math.round(cfg.hudX * travelX),
                MARGIN + (int) Math.round(cfg.hudY * travelY), size, travelX, travelY);
    }

    static void draw(GuiGraphics g, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ClientMapSettings cfg = ClientMapSettings.get();
        Layout area = layout(g.guiWidth(), g.guiHeight());
        int rotation = cfg.hud(ClientMapSettings.NORTH_UP) ? 0 : Math.round((mc.player.getYRot() + 180) / 2) * 2;
        boolean reduced = area.size() * mc.getWindow().getGuiScale() * cfg.hudZoom < 240.0;
        Frame next = new Frame(mc.player.getBlockX(), mc.player.getBlockZ(), rotation, cfg.hudZoom, cfg.round,
                reduced, cfg.style, CartographClient.revision());
        if (!next.equals(previous) && (texture == null || lastFrameTick != mc.level.getGameTime() || editing)) {
            update(next);
            lastFrameTick = mc.level.getGameTime();
        }
        if (textureId == null || previous == null) return;

        g.blit(textureId, area.x(), area.y(), area.size(), area.size(), 0, 0, RASTER, RASTER, RASTER, RASTER);
        Frame drawn = previous;
        double angle = Math.toRadians(drawn.rotation()), scale = area.size() * drawn.zoom() / 240.0;
        if (cfg.hud(ClientMapSettings.ICONS)) drawIcons(g, mc, cfg, area, drawn, angle, scale);
        if (cfg.hud(ClientMapSettings.REGION_NAMES) && cfg.layer(ClientMapSettings.REGIONS)) {
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
        if (cfg.hud(ClientMapSettings.CARDINALS)) drawCardinals(g, mc, area, angle);
        if (cfg.hud(ClientMapSettings.COORDS)) {
            String pos = "X " + mc.player.getBlockX() + " · Z " + mc.player.getBlockZ();
            int half = mc.font.width(pos) / 2 + 6, middle = area.x() + area.size() / 2;
            AtlasChrome.rounded(g, middle - half, area.y() + area.size() + 3, half * 2, 15, 4, AtlasChrome.PANEL);
            g.drawCenteredString(mc.font, pos, middle, area.y() + area.size() + 7, AtlasChrome.CREAM);
        }
        if (editing) {
            int x = area.x() + area.size(), y = area.y() + area.size();
            AtlasChrome.rounded(g, x - 12, y - 12, 20, 20, 4, AtlasChrome.GOLD);
            AtlasChrome.line(g, x - 8, y + 4, x + 4, y - 8, AtlasChrome.INSET, 1);
            AtlasChrome.line(g, x - 3, y + 4, x + 4, y - 3, AtlasChrome.INSET, 1);
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
                AtlasChrome.rounded(g, at[0] - 6, at[1] - 6, 12, 12, cfg.round ? 4 : 2, 0xfff1dfb8);
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
