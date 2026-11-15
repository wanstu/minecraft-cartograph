package com.wanstu.cartographv3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Loopback-only HTTP bridge for the standalone Cartograph WebUI.
 *
 * The browser is a frontend. It never reads Minecraft memory directly.
 * All game data/actions are exposed through CartographBackendApi on the
 * Minecraft client thread.
 */
final class CartographWebUiServer implements AutoCloseable {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.webui");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_BODY = 1_048_576;

    private record CachedTile(long fingerprint, long styleSignature, byte[] rgb24) {}
    private record MarkerPayload(
            String name,
            String icon,
            Integer primaryColor,
            Integer accentColor,
            Integer lineColor,
            String description,
            Integer x,
            Integer y,
            Integer z) {}
    private record ViewportPayload(
            double centerX,
            double centerZ,
            double zoom,
            int width,
            int height) {}
    private record RuntimeSettingsPayload(
            Integer requestBatchSize,
            Integer liveScanIntervalTicks) {}
    private record PointPayload(int x, int z) {}

    private final MinecraftCartographBackend backend = new MinecraftCartographBackend();
    private final Map<ChunkKey, CachedTile> renderedTiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService clients = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "cartograph-webui-http");
        thread.setDaemon(true);
        return thread;
    });

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile long lastClientActivityMillis;
    private String token;
    private ClientLevel backendWorld;
    private boolean backendOpen;

    synchronized String openInBrowser() {
        start();
        lastClientActivityMillis = System.currentTimeMillis();
        ensureBackendWorld();
        String url = baseUrl() + "/?token=" + token;
        try {
            Util.getPlatform().openUri(URI.create(url));
        } catch (RuntimeException failure) {
            LOG.warn("Unable to open Cartograph WebUI URL {}", url, failure);
        }
        return url;
    }

    synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket(
                    0, 32, InetAddress.getByName("127.0.0.1"));
            token = UUID.randomUUID().toString().replace("-", "");
            running = true;
            acceptThread = new Thread(this::acceptLoop, "cartograph-webui-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            LOG.info("Cartograph WebUI listening on {}", baseUrl());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to start Cartograph WebUI", failure);
        }
    }

    void tick() {
        if (!running) return;
        long idleMillis = System.currentTimeMillis() - lastClientActivityMillis;
        if (lastClientActivityMillis > 0L && idleMillis > 10_000L) {
            if (backendOpen) {
                backend.close();
                backendOpen = false;
            }
            return;
        }
        ensureBackendWorld();
        if (backendOpen) backend.tick();
    }

    boolean running() {
        return running;
    }

    private synchronized void ensureBackendWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel next = minecraft.level;
        if (backendWorld != next) {
            if (backendOpen) {
                backend.close();
                backendOpen = false;
            }
            backendWorld = next;
            renderedTiles.clear();
        }

        if (next != null && !backendOpen) {
            SatelliteSurfaceRenderer.beginMapSession();
            backend.open();
            backendOpen = true;
        }
    }

    private String baseUrl() {
        ServerSocket current = server;
        int port = current == null ? 0 : current.getLocalPort();
        return "http://127.0.0.1:" + port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                clients.execute(() -> handle(socket));
            } catch (IOException failure) {
                if (running) LOG.warn("Cartograph WebUI accept failed", failure);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(10_000);

            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isBlank()) return;
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length < 2) {
                writeText(output, 400, "Bad Request", "text/plain; charset=utf-8");
                return;
            }

            String method = requestParts[0].toUpperCase(Locale.ROOT);
            String target = requestParts[1];
            Map<String, String> headers = new HashMap<>();
            for (;;) {
                String line = readLine(input);
                if (line == null || line.isEmpty()) break;
                int split = line.indexOf(':');
                if (split > 0) {
                    headers.put(
                            line.substring(0, split).trim().toLowerCase(Locale.ROOT),
                            line.substring(split + 1).trim());
                }
            }

            int contentLength = parseInt(headers.get("content-length"), 0);
            if (contentLength < 0 || contentLength > MAX_BODY) {
                writeText(output, 413, "Payload Too Large", "text/plain; charset=utf-8");
                return;
            }
            byte[] body = contentLength == 0 ? new byte[0] : input.readNBytes(contentLength);

            URI uri;
            try {
                uri = URI.create("http://127.0.0.1" + target);
            } catch (RuntimeException invalid) {
                writeText(output, 400, "Bad Request", "text/plain; charset=utf-8");
                return;
            }

            String path = uri.getPath();
            if ("OPTIONS".equals(method)) {
                writeResponse(output, 204, "No Content", "text/plain", new byte[0], Map.of());
                return;
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] page = resource("/webui/index.html");
                if (page == null) {
                    writeText(output, 500, "WebUI resource missing", "text/plain; charset=utf-8");
                } else {
                    writeResponse(
                            output, 200, "OK", "text/html; charset=utf-8", page,
                            Map.of(
                                    "Cache-Control", "no-store",
                                    "X-Content-Type-Options", "nosniff",
                                    "Referrer-Policy", "no-referrer"));
                }
                return;
            }

            if ("/app.js".equals(path)) {
                byte[] script = resource("/webui/app.js");
                if (script == null) {
                    writeText(output, 500, "WebUI script missing", "text/plain; charset=utf-8");
                } else {
                    writeResponse(
                            output, 200, "OK", "text/javascript; charset=utf-8", script,
                            Map.of("Cache-Control", "no-store"));
                }
                return;
            }

            if (!path.startsWith("/api/")) {
                writeText(output, 404, "Not Found", "text/plain; charset=utf-8");
                return;
            }
            if (!authorized(headers, uri)) {
                writeText(output, 403, "Forbidden", "text/plain; charset=utf-8");
                return;
            }

            lastClientActivityMillis = System.currentTimeMillis();
            route(output, method, uri, headers, body);
        } catch (Exception failure) {
            LOG.debug("Cartograph WebUI request failed", failure);
        }
    }

    private void route(
            BufferedOutputStream output,
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] body) throws Exception {
        String path = uri.getPath();
        Map<String, String> query = query(uri);

        if ("GET".equals(method) && "/api/v1/health".equals(path)) {
            json(output, 200, Map.of("ok", true, "version", "v3.1-webui"));
            return;
        }

        if ("GET".equals(method) && "/api/v1/state".equals(path)) {
            json(output, 200, onClient(this::stateSnapshot));
            return;
        }

        if ("POST".equals(method) && "/api/v1/viewport".equals(path)) {
            ViewportPayload payload = GSON.fromJson(
                    new String(body, StandardCharsets.UTF_8), ViewportPayload.class);
            onClient(() -> {
                backend.updateViewport(
                        new CartographBackendApi.Viewport(
                                payload.centerX(),
                                payload.centerZ(),
                                Math.max(0.03125, payload.zoom()),
                                Math.max(1, payload.width()),
                                Math.max(1, payload.height()),
                                false),
                        false);
                return null;
            });
            json(output, 200, Map.of("ok", true));
            return;
        }

        if ("GET".equals(method) && "/api/v1/tile".equals(path)) {
            int x = parseRequiredInt(query, "x");
            int z = parseRequiredInt(query, "z");
            TileReply reply = onClient(() -> tileReply(x, z));
            if (reply == null) {
                writeResponse(output, 204, "No Content", "application/octet-stream", new byte[0], Map.of());
                return;
            }
            String ifNoneMatch = headers.get("if-none-match");
            if (reply.etag.equals(ifNoneMatch)) {
                writeResponse(
                        output, 304, "Not Modified",
                        "application/vnd.cartograph.rgb24",
                        new byte[0],
                        Map.of("ETag", reply.etag, "Cache-Control", "no-cache"));
                return;
            }
            writeResponse(
                    output, 200, "OK",
                    "application/vnd.cartograph.rgb24",
                    reply.body,
                    Map.of(
                            "ETag", reply.etag,
                            "Cache-Control", "no-cache",
                            "X-Cartograph-Tile-Size", Integer.toString(SurfaceTile.SIZE)));
            return;
        }

        if ("GET".equals(method) && "/api/v1/point".equals(path)) {
            int x = parseRequiredInt(query, "x");
            int z = parseRequiredInt(query, "z");
            json(output, 200, onClient(() -> Map.of(
                    "x", x,
                    "y", backend.surfaceYAt(x, z),
                    "z", z,
                    "biome", backend.biomeLabelAt(x, z))));
            return;
        }

        if ("POST".equals(method) && "/api/v1/teleport".equals(path)) {
            PointPayload payload = GSON.fromJson(
                    new String(body, StandardCharsets.UTF_8), PointPayload.class);
            onClient(() -> {
                backend.teleport(payload.x(), payload.z());
                return null;
            });
            json(output, 200, Map.of("ok", true));
            return;
        }

        if ("POST".equals(method) && "/api/v1/runtime-settings".equals(path)) {
            RuntimeSettingsPayload payload = GSON.fromJson(
                    new String(body, StandardCharsets.UTF_8), RuntimeSettingsPayload.class);
            onClient(() -> {
                if (payload.requestBatchSize() != null) {
                    CartographSettings.setRequestBatchSize(payload.requestBatchSize());
                }
                if (payload.liveScanIntervalTicks() != null) {
                    backend.setLiveScanInterval(payload.liveScanIntervalTicks());
                }
                return null;
            });
            json(output, 200, Map.of("ok", true));
            return;
        }

        if ("POST".equals(method) && "/api/v1/markers".equals(path)) {
            MarkerPayload payload = markerPayload(body);
            Map<String, Object> result = onClient(() -> {
                MapMarkerStore.Marker marker = backend.createMarker(
                        required(payload.x(), "x"),
                        required(payload.y(), "y"),
                        required(payload.z(), "z"),
                        payload.name(),
                        icon(payload.icon()),
                        color(payload.primaryColor(), MapMarkerStore.DEFAULT_PRIMARY_COLOR),
                        color(payload.accentColor(), 0xffd34e),
                        color(payload.lineColor(), MapMarkerStore.DEFAULT_LINE_COLOR),
                        payload.description());
                return markerJson(marker);
            });
            json(output, 200, result);
            return;
        }

        if (path.startsWith("/api/v1/markers/")) {
            String tail = path.substring("/api/v1/markers/".length());
            boolean reset = tail.endsWith("/reset");
            String idText = reset ? tail.substring(0, tail.length() - "/reset".length()) : tail;
            UUID id = UUID.fromString(idText);

            if ("POST".equals(method) && reset) {
                onClient(() -> {
                    backend.resetAutomaticMarker(id);
                    return null;
                });
                json(output, 200, Map.of("ok", true));
                return;
            }

            if ("DELETE".equals(method) && !reset) {
                onClient(() -> {
                    backend.deleteMarker(id);
                    return null;
                });
                json(output, 200, Map.of("ok", true));
                return;
            }

            if ("PUT".equals(method) && !reset) {
                MarkerPayload payload = markerPayload(body);
                onClient(() -> {
                    MapMarkerStore.Marker existing = MapMarkerStore.find(id).orElse(null);
                    if (existing == null) return null;
                    backend.updateMarker(
                            id,
                            payload.x() == null ? existing.x : payload.x(),
                            payload.y() == null ? existing.y : payload.y(),
                            payload.z() == null ? existing.z : payload.z(),
                            payload.name() == null ? existing.name : payload.name(),
                            payload.icon() == null ? existing.icon : icon(payload.icon()),
                            payload.primaryColor() == null ? existing.primary() : payload.primaryColor(),
                            payload.accentColor() == null ? existing.accent() : payload.accentColor(),
                            payload.lineColor() == null ? existing.line() : payload.lineColor(),
                            payload.description() == null ? existing.description : payload.description());
                    return null;
                });
                json(output, 200, Map.of("ok", true));
                return;
            }
        }

        writeText(output, 404, "Not Found", "text/plain; charset=utf-8");
    }

    private Map<String, Object> stateSnapshot() {
        CartographBackendApi.PlayerState player = backend.player();
        List<Map<String, Object>> markers = backend.markers().stream()
                .map(this::markerJson)
                .toList();
        Map<String, Object> playerJson = new HashMap<>();
        playerJson.put("present", player.present());
        playerJson.put("x", player.x());
        playerJson.put("y", player.y());
        playerJson.put("z", player.z());
        playerJson.put("yaw", player.yaw());

        Map<String, Object> result = new HashMap<>();
        result.put("connected", Minecraft.getInstance().level != null);
        result.put("dimension", backend.dimension());
        result.put("worldId", String.valueOf(MapClientCache.worldId()));
        result.put("player", playerJson);
        result.put("markers", markers);
        result.put(
                "structurePresets",
                java.util.Arrays.stream(MapMarkerStore.StructurePreset.values())
                        .map(preset -> Map.of(
                                "id", preset.name().toLowerCase(Locale.ROOT),
                                "label", preset.label,
                                "icon", preset.defaultIcon.name().toLowerCase(Locale.ROOT),
                                "primaryColor", hex(preset.defaultPrimary),
                                "accentColor", hex(preset.defaultAccent),
                                "lineColor", hex(preset.defaultLine)))
                        .toList());
        result.put("runtime", Map.of(
                "cachedCount", backend.cachedCount(),
                "pendingCount", backend.pendingCount(),
                "requestBatchSize", CartographSettings.requestBatchSize(),
                "liveScanIntervalTicks", CartographSettings.liveScanIntervalTicks()));
        return result;
    }

    private Map<String, Object> markerJson(MapMarkerStore.Marker marker) {
        MapMarkerStore.StructurePreset preset =
                MapMarkerStore.structurePreset(marker.structureId);
        boolean structure =
                marker.origin == MapMarkerStore.Origin.AUTO && !marker.modifiedAuto;

        Map<String, Object> result = new HashMap<>();
        result.put("id", marker.id);
        result.put("kind", structure ? "structure" : "custom");
        result.put("origin", marker.origin.name());
        result.put("modifiedAuto", marker.modifiedAuto);
        result.put("x", marker.x);
        result.put("y", marker.y);
        result.put("z", marker.z);
        result.put("name", marker.name == null ? "" : marker.name);
        result.put("icon", marker.icon == null
                ? "pin"
                : marker.icon.name().toLowerCase(Locale.ROOT));
        result.put("primaryColor", hex(marker.primary()));
        result.put("accentColor", hex(marker.accent()));
        result.put("lineColor", hex(marker.line()));
        result.put("description", marker.description == null ? "" : marker.description);
        result.put("structureId", marker.structureId == null ? "" : marker.structureId);
        result.put("preset", preset == null ? "" : preset.name().toLowerCase(Locale.ROOT));
        return result;
    }

    private record TileReply(String etag, byte[] body) {}

    private TileReply tileReply(int x, int z) {
        String dimension = backend.dimension();
        if (dimension == null || dimension.isBlank()) return null;
        ChunkKey key = new ChunkKey(dimension, x, z);
        SurfaceTile facts = backend.tile(key);
        if (facts == null || !facts.hasSurfaceFacts()) return null;

        long fingerprint = facts.fingerprint64();
        long signature = SatelliteSurfaceRenderer.renderSignature();
        CachedTile cached = renderedTiles.get(key);
        if (cached == null
                || cached.fingerprint() != fingerprint
                || cached.styleSignature() != signature) {
            int[] rgb = SatelliteSurfaceRenderer.render(facts).rgb();
            byte[] body = new byte[SurfaceTile.PIXELS * 3];
            for (int i = 0; i < SurfaceTile.PIXELS; i++) {
                int color = rgb[i];
                body[i * 3] = (byte) ((color >>> 16) & 255);
                body[i * 3 + 1] = (byte) ((color >>> 8) & 255);
                body[i * 3 + 2] = (byte) (color & 255);
            }
            cached = new CachedTile(fingerprint, signature, body);
            renderedTiles.put(key, cached);
        }
        String etag = "\"" + Long.toUnsignedString(fingerprint)
                + "-" + Long.toUnsignedString(signature) + "\"";
        return new TileReply(etag, cached.rgb24());
    }

    private MarkerPayload markerPayload(byte[] body) {
        return GSON.fromJson(
                new String(body, StandardCharsets.UTF_8), MarkerPayload.class);
    }

    private static MapMarkerStore.Icon icon(String raw) {
        if (raw == null || raw.isBlank()) return MapMarkerStore.Icon.PIN;
        try {
            return MapMarkerStore.Icon.valueOf(
                    raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MapMarkerStore.Icon.PIN;
        }
    }

    private static int color(Integer value, int fallback) {
        return value == null ? fallback : value & 0x00ffffff;
    }

    private static int required(Integer value, String field) {
        if (value == null) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private boolean authorized(Map<String, String> headers, URI uri) {
        String supplied = headers.get("x-cartograph-token");
        if (supplied == null || supplied.isBlank()) {
            supplied = query(uri).get("token");
        }
        return token != null && token.equals(supplied);
    }

    private <T> T onClient(Supplier<T> supplier) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) return supplier.get();

        CompletableFuture<T> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                ensureBackendWorld();
                future.complete(supplier.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Minecraft client thread request timed out", failure);
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream input = CartographWebUiServer.class.getResourceAsStream(path)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("&")) {
            int split = part.indexOf('=');
            String key = decode(split < 0 ? part : part.substring(0, split));
            String value = decode(split < 0 ? "" : part.substring(split + 1));
            out.put(key, value);
        }
        return out;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static int parseRequiredInt(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null) throw new IllegalArgumentException("Missing query: " + key);
        return Integer.parseInt(value);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0x00ffffff);
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() < 16_384) {
            int next = input.read();
            if (next < 0) break;
            if (previous == '\r' && next == '\n') {
                byte[] raw = bytes.toByteArray();
                int length = raw.length;
                if (length > 0 && raw[length - 1] == '\r') length--;
                return new String(raw, 0, length, StandardCharsets.ISO_8859_1);
            }
            bytes.write(next);
            previous = next;
        }
        if (bytes.size() == 0) return null;
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static void json(
            BufferedOutputStream output,
            int status,
            Object value) throws IOException {
        writeResponse(
                output,
                status,
                status == 200 ? "OK" : "Error",
                "application/json; charset=utf-8",
                GSON.toJson(value).getBytes(StandardCharsets.UTF_8),
                Map.of("Cache-Control", "no-store"));
    }

    private static void writeText(
            BufferedOutputStream output,
            int status,
            String text,
            String contentType) throws IOException {
        writeResponse(
                output,
                status,
                text,
                contentType,
                text.getBytes(StandardCharsets.UTF_8),
                Map.of("Cache-Control", "no-store"));
    }

    private static void writeResponse(
            BufferedOutputStream output,
            int status,
            String reason,
            String contentType,
            byte[] body,
            Map<String, String> extraHeaders) throws IOException {
        StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n");
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            headers.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    @Override
    public synchronized void close() {
        running = false;
        backendWorld = null;
        renderedTiles.clear();
        if (backendOpen) {
            backend.close();
            backendOpen = false;
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {}
            server = null;
        }
        clients.shutdownNow();
    }
}
