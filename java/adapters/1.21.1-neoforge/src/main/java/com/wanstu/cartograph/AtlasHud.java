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
    private static final int BOOK_RASTER_W = 256, BOOK_RASTER_H = 160;
    private static DynamicTexture bookTexture;
    private static ResourceLocation bookTextureId;
    private static Frame bookPrevious;
    private static long bookFrameTick = -1;
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
        drawWithin(g, editing, layout(g.guiWidth(), g.guiHeight()));
    }

    /** A fixed-position antique book, matching the actual physical hand:
     * main hand opens centrally, offhand rests smaller on lower left.
     * No screen-position control is needed or provided. */
    static void drawHeld(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        boolean main = mc.player.getMainHandItem().is(CartographMod.ATLAS.get());
        int width = Math.clamp((int) (Math.min(g.guiWidth() * (main ? .37 : .26),
                g.guiHeight() * (main ? .88 : .62))), main ? 140 : 90, main ? 340 : 225);
        int height = Math.max(52, (int) Math.round(width * .65));
        int x = main ? (g.guiWidth() - width) / 2 : Math.max(7, g.guiWidth() / 24);
        int y = Math.max(9, g.guiHeight() - height - (main ? 55 : 62));

        // Original procedural folio artwork; no assets from other mods.
        // Draw hands BEHIND the book so the item occupies a natural portion
        // of the view instead of becoming a huge HUD pinned to the right.
        if (main) {
            g.fill(x + width / 13, y + height - 8, x + width / 6,
                    g.guiHeight() - 10, 0xffdeba94);
            g.fill(x + width * 5 / 6, y + height - 8, x + width * 12 / 13,
                    g.guiHeight() - 10, 0xffdeba94);
        } else {
            g.fill(x + width / 13, y + height - 8, x + width / 5,
                    g.guiHeight() - 10, 0xffdeba94);
        }
        AtlasChrome.rounded(g, x - 5, y - 5, width + 10, height + 11, 6, 0xff38281d);
        AtlasChrome.rounded(g, x - 2, y - 3, width + 4, height + 5, 4, 0xff80502f);
        AtlasChrome.rounded(g, x + 2, y + 1, width / 2 - 2, height - 3, 2, 0xfff1d9a7);
        AtlasChrome.rounded(g, x + width / 2, y + 1, width / 2 - 2, height - 3, 2, 0xffe9cfa0);

        int mx = x + 8, my = y + 8, mw = width - 16, mh = height - 18;
        ClientMapSettings cfg = ClientMapSettings.get();
        boolean northUp = cfg.hud(ClientMapSettings.NORTH_UP);
        int rotation = northUp ? 0 : Math.round((mc.player.getYRot() + 180) / 2) * 2;
        double actualZoom = cfg.hudZoom;
        Frame frame = new Frame(mc.player.getBlockX(), mc.player.getBlockZ(), rotation,
                actualZoom, false, false, cfg.style, CartographClient.revision());
        if (!frame.equals(bookPrevious) &&
                (bookTexture == null || bookFrameTick != mc.level.getGameTime())) {
            updateBook(frame);
            bookFrameTick = mc.level.getGameTime();
        }
        if (bookTextureId != null) {
            g.blit(bookTextureId, mx, my, mw, mh, 0, 0, BOOK_RASTER_W, BOOK_RASTER_H,
                    BOOK_RASTER_W, BOOK_RASTER_H);
        }
        // Page seam and book edges stay on top of the inked terrain.
        g.fill(x + width / 2 - 2, y + 2, x + width / 2 + 2, y + height - 3, 0x88684730);
        g.fill(x + 3, y + height - 5, x + width - 3, y + height - 3, 0xffb58a59);

        if (cfg.hud(ClientMapSettings.ICONS))
            drawBookIcons(g, mc, frame, mx, my, mw, mh);
        if (CartographClient.state().compassInstalled())
            AtlasChrome.arrow(g, mx + mw * .5, my + mh * .5,
                    Math.toRadians(mc.player.getYRot() + 180 - rotation), .66);
        if (cfg.hud(ClientMapSettings.CARDINALS))
            drawBookCardinals(g, mx, my, mw, mh, rotation);
        if (cfg.hud(ClientMapSettings.COORDS)) {
            String coords = "X " + mc.player.getBlockX() + "   Z " + mc.player.getBlockZ();
            int tw = mc.font.width(coords);
            if (tw + 10 < mw) {
                int bx = mx + (mw - tw) / 2 - 5, by = my + mh - 13;
                AtlasChrome.rounded(g, bx, by, tw + 10, 13, 2, 0xb1332a22);
                g.drawString(mc.font, coords, bx + 5, by + 2, 0xfff8e6ba, false);
            }
        }
    }

    private static void drawBookCardinals(GuiGraphics g, int x, int y, int w, int h,
                                          double rotation) {
        Minecraft mc = Minecraft.getInstance();
        String[] names = {"N", "E", "S", "W"};
        double angle = Math.toRadians(rotation);
        for (int i = 0; i < names.length; i++) {
            double a = Math.PI * .5 * i - angle;
            int cx = x + w / 2 + (int) Math.round(Math.sin(a) * (w / 2 - 13));
            int cy = y + h / 2 - (int) Math.round(Math.cos(a) * (h / 2 - 14));
            AtlasChrome.rounded(g, cx - 7, cy - 7, 14, 14, 2, 0xd2e8d4a7);
            g.drawCenteredString(mc.font, names[i], cx, cy - 4, 0xff382c22);
        }
    }

    private static void drawBookIcons(GuiGraphics g, Minecraft mc, Frame frame,
                                     int x, int y, int w, int h) {
        ClientMapSettings cfg = ClientMapSettings.get();
        Set<String> seen = new HashSet<>();
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            if (cfg.layer(ClientMapSettings.WAYPOINTS))
                for (AtlasMarker marker : page.markers()) {
                    if (!seen.add(marker.id())
                            || !CartographClient.surveyed(marker.x(), marker.z())) continue;
                    int[] pos = projectBook(frame, x, y, w, h, marker.x(), marker.z());
                    if (pos == null) continue;
                    AtlasChrome.rounded(g, pos[0] - 4, pos[1] - 4, 9, 9, 2, 0xffeed8a5);
                    g.drawCenteredString(mc.font, AtlasScreen.markerGlyph(marker.icon()),
                            pos[0], pos[1] - 4, 0xff000000 | marker.color());
                }
            if (cfg.layer(ClientMapSettings.STRUCTURES))
                for (SurfaceStructure structure : page.structures()) {
                    int[] pos = projectBook(frame, x, y, w, h, structure.x(), structure.z());
                    if (pos == null) continue;
                    AtlasChrome.rounded(g, pos[0] - 2, pos[1] - 2, 5, 5, 1, 0xff96713e);
                }
        }
    }

    private static int[] projectBook(Frame frame, int x, int y, int w, int h, int wx, int wz) {
        double angle = Math.toRadians(frame.rotation()), co = Math.cos(angle), si = Math.sin(angle);
        double extentX = 120.0 * BOOK_RASTER_W / BOOK_RASTER_H / frame.zoom();
        double extentZ = 120.0 / frame.zoom();
        double dx = wx - frame.x(), dz = wz - frame.z();
        double u = (dx * co + dz * si) / extentX, v = (-dx * si + dz * co) / extentZ;
        if (Math.abs(u) > .92 || Math.abs(v) > .88) return null;
        return new int[]{x + w / 2 + (int) Math.round(u * w / 2),
                y + h / 2 + (int) Math.round(v * h / 2)};
    }

    private static void updateBook(Frame next) {
        Minecraft mc = Minecraft.getInstance();
        if (bookTexture == null) {
            bookTexture = new DynamicTexture(new NativeImage(BOOK_RASTER_W, BOOK_RASTER_H, true));
            bookTextureId = mc.getTextureManager().register("cartograph/held-book", bookTexture);
        }
        NativeImage image = bookTexture.getPixels();
        if (image == null) return;
        double halfX = 120.0 * BOOK_RASTER_W / BOOK_RASTER_H / next.zoom();
        double halfZ = 120.0 / next.zoom();
        double boundary = Math.hypot(halfX, halfZ);
        int px0 = Math.floorDiv((int) Math.floor(next.x() - boundary), 128);
        int pz0 = Math.floorDiv((int) Math.floor(next.z() - boundary), 128);
        int px1 = Math.floorDiv((int) Math.ceil(next.x() + boundary), 128);
        int pz1 = Math.floorDiv((int) Math.ceil(next.z() + boundary), 128);
        int stride = px1 - px0 + 1;
        NativeImage[] pages = new NativeImage[stride * (pz1 - pz0 + 1)];
        for (int pz = pz0; pz <= pz1; pz++) for (int px = px0; px <= px1; px++)
            pages[(pz - pz0) * stride + px - px0] = CartographClient.image(px, pz);
        double angle = Math.toRadians(next.rotation()), co = Math.cos(angle), si = Math.sin(angle);
        int blank = PixelPageTexture.abgr(PixelPageTexture.PAPER);
        for (int y = 0; y < BOOK_RASTER_H; y++) for (int x = 0; x < BOOK_RASTER_W; x++) {
            double u = 2.0 * (x + .5) / BOOK_RASTER_W - 1;
            double v = 2.0 * (y + .5) / BOOK_RASTER_H - 1;
            int wx = (int) Math.floor(next.x() + co * halfX * u - si * halfZ * v);
            int wz = (int) Math.floor(next.z() + si * halfX * u + co * halfZ * v);
            int ix = Math.floorDiv(wx, 128) - px0, iz = Math.floorDiv(wz, 128) - pz0;
            NativeImage source = ix >= 0 && ix < stride && iz >= 0 && iz <= pz1 - pz0
                    ? pages[iz * stride + ix] : null;
            int pixel = source == null ? blank : PixelPageTexture.sample(source,
                    next.style(), Math.floorMod(wx, 128), Math.floorMod(wz, 128), false);
            image.setPixelRGBA(x, y, pixel);
        }
        bookTexture.upload();
        bookPrevious = next;
    }

    private static void drawWithin(GuiGraphics g, boolean editing, Layout area) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ClientMapSettings cfg = ClientMapSettings.get();
        int rotation = cfg.hud(ClientMapSettings.NORTH_UP) ? 0
                : Math.round((mc.player.getYRot() + 180) / 2) * 2;
        boolean reduced = area.size() * mc.getWindow().getGuiScale() * cfg.hudZoom < 240.0;
        Frame next = new Frame(mc.player.getBlockX(), mc.player.getBlockZ(), rotation,
                cfg.hudZoom, cfg.round, reduced, cfg.style, CartographClient.revision());
        if (!next.equals(previous) && (texture == null || lastFrameTick != mc.level.getGameTime() || editing)) {
            update(next);
            lastFrameTick = mc.level.getGameTime();
        }
        if (textureId == null || previous == null) return;

        g.blit(textureId, area.x(), area.y(), area.size(), area.size(), 0, 0, RASTER, RASTER, RASTER, RASTER);
        Frame drawn = previous;
        double angle = Math.toRadians(drawn.rotation()), scale = area.size() * drawn.zoom() / 240.0;
        if (cfg.hud(ClientMapSettings.ICONS))
            drawIcons(g, mc, cfg, area, drawn, angle, scale);
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
            // Keep one compact line INSIDE the circular map silhouette, not
            // inside its square bounding box where the old 2-row black bar
            // extended out of the circle at the lower left.
            String coords = "X" + mc.player.getBlockX() + " Z" + mc.player.getBlockZ();
            int centerX = area.x() + area.size() / 2;
            int textY = area.y() + (int) (area.size() * .73);
            double radialY = textY + 6 - (area.y() + area.size() / 2.0);
            double radius = area.size() * .46;
            int chord = drawn.round() ? (int) Math.floor(
                    2.0 * Math.sqrt(Math.max(0, radius * radius - radialY * radialY)))
                    : area.size() - 12;
            int allowed = Math.max(12, Math.min(area.size() - 10, chord - 8));
            float labelScale = (float) Math.min(1.0, allowed / (double) Math.max(1, mc.font.width(coords)));
            if (labelScale >= .35f) {
                g.pose().pushPose();
                g.pose().translate(centerX, textY, 0);
                g.pose().scale(labelScale, labelScale, 1);
                g.drawCenteredString(mc.font, coords, 0, 0, 0xfff7e9cf);
                g.pose().popPose();
            }
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
        float markerScale = (float) Math.clamp(area.size() * view.zoom() / 240.0, .34, 1.0);
        Set<String> seen = new HashSet<>();
        for (MapNetwork.Page page : CartographClient.cachedPages()) {
            if (cfg.layer(ClientMapSettings.WAYPOINTS)) for (AtlasMarker marker : page.markers()) {
                int[] at = project(area, view, angle, scale, marker.x(), marker.z(), 8);
                if (at == null || !seen.add(marker.id()) || !CartographClient.surveyed(marker.x(), marker.z())) continue;
                g.pose().pushPose();
                g.pose().translate(at[0], at[1], 0);
                g.pose().scale(markerScale, markerScale, 1);
                AtlasChrome.rounded(g, -6, -6, 12, 12, view.round() ? 4 : 2, 0xfff1dfb8);
                g.drawCenteredString(mc.font, AtlasScreen.markerGlyph(marker.icon()), 0, -4,
                        0xff000000 | marker.color());
                g.pose().popPose();
            }
            if (cfg.layer(ClientMapSettings.STRUCTURES)) for (SurfaceStructure point : page.structures()) {
                int[] at = project(area, view, angle, scale, point.x(), point.z(), 7);
                if (at != null) {
                    int half = Math.max(2, Math.round(3 * markerScale));
                    AtlasChrome.rounded(g, at[0] - half, at[1] - half,
                            2 * half + 1, 2 * half + 1, 1, AtlasChrome.GOLD);
                }
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
        if (bookTextureId != null)
            Minecraft.getInstance().getTextureManager().release(bookTextureId);
        bookTexture = null; bookTextureId = null; bookPrevious = null; bookFrameTick = -1;
    }
}
