package com.wanstu.cartographv3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.MessageDigest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Loopback-only static HTTP host and authenticated WebSocket runtime for Cartograph WebUI.
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
    private static final long STARTUP_GRACE_MILLIS = 30_000L;
    private static final long IDLE_RELEASE_MILLIS = 10_000L;

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
    private record StructurePresetChange(String icon, Integer color) {}
    private record StructurePresetsPayload(
            boolean reset, Map<String, StructurePresetChange> changes) {}
    private record ViewportPayload(
            double centerX,
            double centerZ,
            double zoom,
            int width,
            int height) {}
    private record TileBatchItem(int x, int z, String etag) {}
    private record TileBatchPayload(List<TileBatchItem> tiles) {}
    private record TileBatchResult(byte[] bytes, Map<ChunkKey, String> etags) {}
    private record ViewportSelection(
            String dimension,
            int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ,
            int centerChunkX, int centerChunkZ,
            List<ChunkKey> cachedKeys) {}
    private record WebSocketFrame(int opcode, byte[] payload) {}
    private record RuntimeSettingsPayload(
            Integer requestBatchSize,
            Integer liveScanIntervalTicks,
            String frontendTarget) {}

    private static final String FRONTEND_SESSION = "web";

    private final CartographBackendRuntime backendRuntime;
    private final CartographBackendApi backend;
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
    private volatile long startupGraceUntilMillis;
    private final AtomicInteger activeWebSessions = new AtomicInteger();
    private String token;
    private ClientLevel backendWorld;
    private boolean backendOpen;
    private boolean pauseOverrideActive;
    private boolean previousPauseOnLostFocus;

    CartographWebUiServer(CartographBackendRuntime backendRuntime) {
        this.backendRuntime = backendRuntime;
        this.backend = backendRuntime.api();
    }

    synchronized String openInBrowser() {
        start();
        long now = System.currentTimeMillis();
        lastClientActivityMillis = now;
        startupGraceUntilMillis = now + STARTUP_GRACE_MILLIS;
        ensureBackendWorld();
        keepSingleplayerRunningWhileWebUiActive();
        String url = baseUrl() + "/?token=" + token;
        try {
            MapClientAccess.openUri(URI.create(url));
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
        if (activeWebSessions.get() > 0) {
            ensureBackendWorld();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < startupGraceUntilMillis) {
            ensureBackendWorld();
            return;
        }
        long idleMillis = now - lastClientActivityMillis;
        if (lastClientActivityMillis > 0L && idleMillis > IDLE_RELEASE_MILLIS) {
            if (backendOpen) {
                backendRuntime.release(FRONTEND_SESSION);
                backendOpen = false;
            }
            restorePauseOnLostFocus();
            return;
        }
        ensureBackendWorld();
    }

    boolean running() {
        return running;
    }

    private synchronized void ensureBackendWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel next = minecraft.level;
        if (backendWorld != next) {
            if (backendOpen) {
                backendRuntime.release(FRONTEND_SESSION);
                backendOpen = false;
            }
            restorePauseOnLostFocus();
            backendWorld = next;
            renderedTiles.clear();
        }

        if (next != null && !backendOpen) {
            SatelliteSurfaceRenderer.beginMapSession();
            backendRuntime.acquire(FRONTEND_SESSION);
            backendOpen = true;
        }
        if (next != null && backendOpen) {
            keepSingleplayerRunningWhileWebUiActive();
        }
    }

    private void keepSingleplayerRunningWhileWebUiActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.hasSingleplayerServer() || pauseOverrideActive) return;
        previousPauseOnLostFocus = minecraft.options.pauseOnLostFocus;
        minecraft.options.pauseOnLostFocus = false;
        pauseOverrideActive = true;
        LOG.debug(
                "WebUI active: temporarily disabling pauseOnLostFocus (previous={})",
                previousPauseOnLostFocus);
    }

    private void restorePauseOnLostFocus() {
        if (!pauseOverrideActive) return;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.pauseOnLostFocus = previousPauseOnLostFocus;
        pauseOverrideActive = false;
        LOG.debug(
                "WebUI idle/closed: restored pauseOnLostFocus={}",
                previousPauseOnLostFocus);
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
            if ("GET".equals(method) && "/api/v1/ws".equals(path)) {
                if (!authorized(headers, uri)) {
                    writeText(output, 403, "Forbidden", "text/plain; charset=utf-8");
                    return;
                }
                serveWebSocket(socket, input, output, uri, headers);
                return;
            }
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
            startupGraceUntilMillis = 0L;
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
            byte[] body) throws IOException {
        if ("GET".equals(method) && "/api/v1/health".equals(uri.getPath())) {
            json(output, 200, Map.of("ok", true, "transport", "websocket"));
            return;
        }
        writeText(output, 404, "Not Found", "text/plain; charset=utf-8");
    }

    private Map<String, Object> liveSnapshot() {
        CartographBackendApi.PlayerState player = backend.player();
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
        result.put("runtime", Map.of(
                "cachedCount", backend.cachedCount(),
                "pendingCount", backend.pendingCount()));
        return result;
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
                        .map(CartographWebUiServer::structurePresetJson)
                        .toList());
        result.put("structurePresetRevision", CartographSettings.structurePresetRevision());
        result.put("runtime", Map.of(
                "cachedCount", backend.cachedCount(),
                "pendingCount", backend.pendingCount(),
                "requestBatchSize", CartographSettings.requestBatchSize(),
                "liveScanIntervalTicks", CartographSettings.liveScanIntervalTicks(),
                "frontendTarget", CartographSettings.frontendTarget().id()));
        return result;
    }

    private static Map<String, String> structurePresetJson(MapMarkerStore.StructurePreset preset) {
        var appearance = CartographSettings.structureAppearance(preset);
        String color = hex(appearance.color());
        return Map.of(
                "id", preset.name().toLowerCase(Locale.ROOT),
                "label", preset.label,
                "icon", appearance.icon().name().toLowerCase(Locale.ROOT),
                "primaryColor", color,
                "accentColor", color,
                "lineColor", color,
                "defaultIcon", preset.defaultIcon.name().toLowerCase(Locale.ROOT),
                "defaultAccentColor", hex(preset.defaultAccent));
    }

    private static void updateStructurePresets(StructurePresetsPayload payload) throws IOException {
        Map<MapMarkerStore.StructurePreset, CartographSettings.StructureAppearance> changes =
                new EnumMap<>(MapMarkerStore.StructurePreset.class);
        if (payload.changes() != null) {
            for (var entry : payload.changes().entrySet()) {
                var preset = MapMarkerStore.StructurePreset.valueOf(
                        entry.getKey().toUpperCase(Locale.ROOT));
                var change = entry.getValue();
                if (change == null) throw new IllegalArgumentException("Missing structure preset change");

                var current = payload.reset()
                        ? new CartographSettings.StructureAppearance(preset.defaultIcon, preset.defaultAccent)
                        : CartographSettings.structureAppearance(preset);
                var icon = change.icon() == null ? current.icon()
                        : MapMarkerStore.Icon.valueOf(change.icon().toUpperCase(Locale.ROOT));
                int color = change.color() == null ? current.color() : change.color();
                if (color < 0 || color > 0xffffff) {
                    throw new IllegalArgumentException("Invalid structure preset color");
                }
                changes.put(preset, new CartographSettings.StructureAppearance(icon, color));
            }
        }
        CartographSettings.updateStructurePresets(payload.reset(), changes);
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

    private TileBatchResult tileBatchForSocket(TileBatchPayload payload) {
        List<TileBatchItem> items =
                payload == null || payload.tiles() == null ? List.of() : payload.tiles();
        if (items.size() > 512) {
            throw new IllegalArgumentException("Too many tiles in one batch: " + items.size());
        }

        String dimension = backend.dimension();
        Map<ChunkKey, String> etags = new HashMap<>();
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(8 + items.size() * (32 + SurfaceTile.PIXELS * 3));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x43544231);
            output.writeInt(items.size());
            for (TileBatchItem item : items) {
                output.writeInt(item.x());
                output.writeInt(item.z());
                TileReply reply = tileReply(item.x(), item.z());
                ChunkKey key = dimension == null || dimension.isBlank()
                        ? null : new ChunkKey(dimension, item.x(), item.z());
                if (reply == null) {
                    if (key != null) etags.put(key, "");
                    output.writeByte(0);
                    output.writeShort(0);
                    output.writeInt(0);
                    continue;
                }
                if (key != null) etags.put(key, reply.etag());
                byte[] etag = reply.etag().getBytes(StandardCharsets.UTF_8);
                boolean unchanged = item.etag() != null && !item.etag().isBlank()
                        && item.etag().equals(reply.etag());
                output.writeByte(unchanged ? 2 : 1);
                output.writeShort(etag.length);
                output.write(etag);
                if (unchanged) {
                    output.writeInt(0);
                } else {
                    output.writeInt(reply.body().length);
                    output.write(reply.body());
                }
            }
            output.flush();
            return new TileBatchResult(bytes.toByteArray(), etags);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to build Cartograph WebSocket tile batch", failure);
        }
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

    private void serveWebSocket(
            Socket socket,
            BufferedInputStream input,
            BufferedOutputStream output,
            URI uri,
            Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        String version = headers.get("sec-websocket-version");
        String upgrade = headers.get("upgrade");
        String connection = headers.get("connection");
        if (key == null || !"13".equals(version)
                || upgrade == null || !"websocket".equalsIgnoreCase(upgrade)
                || connection == null
                || !connection.toLowerCase(Locale.ROOT).contains("upgrade")) {
            writeText(output, 400, "Invalid WebSocket handshake", "text/plain; charset=utf-8");
            return;
        }
        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException invalidKey) {
            writeText(output, 400, "Invalid WebSocket key", "text/plain; charset=utf-8");
            return;
        }
        if (decodedKey.length != 16) {
            writeText(output, 400, "Invalid WebSocket key", "text/plain; charset=utf-8");
            return;
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.ISO_8859_1)));
        output.write((
                "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + "Cache-Control: no-store\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        socket.setTcpNoDelay(true);
        new WebSocketSession(socket, input, output).run();
    }

    private final class WebSocketSession {
        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;
        private final Map<ChunkKey, String> browserEtags = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<ChunkKey> viewportTiles =
                new ConcurrentLinkedQueue<>();
        private final Set<ChunkKey> dirtyQueued = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<ChunkKey> dirtyTiles =
                new ConcurrentLinkedQueue<>();
        private volatile String desiredDimension = "";
        private volatile int desiredMinChunkX;
        private volatile int desiredMaxChunkX;
        private volatile int desiredMinChunkZ;
        private volatile int desiredMaxChunkZ;
        private final java.util.function.Consumer<ChunkKey> tileListener = this::queueTile;
        private volatile boolean open = true;
        private String lastLiveJson = "";
        private String lastWorldKey = "";
        private String lastMarkersSignature = "";
        private long lastStructurePresetRevision;
        private long nextLiveAt;
        private long nextPingAt;

        private WebSocketSession(
                Socket socket,
                BufferedInputStream input,
                BufferedOutputStream output) {
            this.socket = socket;
            this.input = input;
            this.output = output;
        }

        private void run() throws Exception {
            activeWebSessions.incrementAndGet();
            long now = System.currentTimeMillis();
            lastClientActivityMillis = now;
            startupGraceUntilMillis = 0L;
            MapClientCache.addTileChangeListener(tileListener);
            try {
                Map<String, Object> initial = onClient(CartographWebUiServer.this::stateSnapshot);
                sendJson(Map.of("type", "state", "data", initial));
                lastWorldKey = worldKey(initial);
                rememberMarkerState(initial);
                lastLiveJson = GSON.toJson(onClient(CartographWebUiServer.this::liveSnapshot));
                nextLiveAt = now + 250L;
                nextPingAt = now + 20_000L;

                while (running && open && !socket.isClosed()) {
                    WebSocketFrame frame = readWebSocketFrame(socket, input);
                    if (frame == null) break;
                    if (frame.opcode() == 1) {
                        lastClientActivityMillis = System.currentTimeMillis();
                        handleMessage(frame.payload());
                    } else if (frame.opcode() == 8) {
                        sendCloseFrame();
                        break;
                    } else if (frame.opcode() == 9) {
                        sendFrame(10, frame.payload());
                    } else if (frame.opcode() != 10 && frame.opcode() != -1) {
                        sendCloseFrame();
                        break;
                    }

                    drainViewportTiles();
                    drainDirtyTiles();
                    publishLiveIfChanged();
                    long current = System.currentTimeMillis();
                    if (current >= nextPingAt) {
                        sendFrame(9, new byte[0]);
                        nextPingAt = current + 20_000L;
                    }
                }
            } finally {
                open = false;
                MapClientCache.removeTileChangeListener(tileListener);
                activeWebSessions.decrementAndGet();
                lastClientActivityMillis = System.currentTimeMillis();
                browserEtags.clear();
                viewportTiles.clear();
                dirtyTiles.clear();
                dirtyQueued.clear();
                desiredDimension = "";
            }
        }

        private void handleMessage(byte[] payloadBytes) {
            JsonObject message;
            try {
                message = GSON.fromJson(
                        new String(payloadBytes, StandardCharsets.UTF_8), JsonObject.class);
                if (message == null) throw new IllegalArgumentException("Empty message");
            } catch (RuntimeException invalid) {
                sendJson(Map.of("type", "error", "error", "Invalid JSON message"));
                return;
            }

            String type = string(message, "type", "");
            var id = message.get("id");
            try {
                switch (type) {
                    case "viewport" -> handleViewport(message);
                    case "point" -> {
                        int x = integer(message, "x");
                        int z = integer(message, "z");
                        Map<String, Object> point = onClient(() -> Map.of(
                                "x", x,
                                "y", backend.surfaceYAt(x, z),
                                "z", z,
                                "biome", backend.biomeLabelAt(x, z)));
                        respond(id, true, point, null);
                    }
                    case "teleport" -> {
                        int x = integer(message, "x");
                        int z = integer(message, "z");
                        onClient(() -> {
                            backend.teleport(x, z);
                            return null;
                        });
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "runtime_settings" -> {
                        RuntimeSettingsPayload settings =
                                payload(message, RuntimeSettingsPayload.class);
                        onClient(() -> {
                            if (settings.requestBatchSize() != null) {
                                CartographSettings.setRequestBatchSize(settings.requestBatchSize());
                            }
                            if (settings.liveScanIntervalTicks() != null) {
                                backend.setLiveScanInterval(settings.liveScanIntervalTicks());
                            }
                            if (settings.frontendTarget() != null) {
                                CartographSettings.setFrontendTarget(
                                        CartographFrontendTarget.parse(settings.frontendTarget()));
                            }
                            return null;
                        });
                        Map<String, Object> state =
                                onClient(CartographWebUiServer.this::stateSnapshot);
                        sendJson(Map.of("type", "runtime", "data", state.get("runtime")));
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "structure_presets" -> {
                        var presets = payload(message, StructurePresetsPayload.class);
                        onClient(() -> {
                            try {
                                updateStructurePresets(presets);
                            } catch (IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                            return null;
                        });
                        pushMarkersIfChanged();
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "marker_create" -> {
                        MarkerPayload marker = payload(message, MarkerPayload.class);
                        onClient(() -> backend.createMarker(
                                required(marker.x(), "x"),
                                required(marker.y(), "y"),
                                required(marker.z(), "z"),
                                marker.name(),
                                icon(marker.icon()),
                                color(marker.primaryColor(), MapMarkerStore.DEFAULT_PRIMARY_COLOR),
                                color(marker.accentColor(), 0xffd34e),
                                color(marker.lineColor(), MapMarkerStore.DEFAULT_LINE_COLOR),
                                marker.description()));
                        pushMarkersIfChanged();
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "marker_update" -> {
                        UUID markerId = UUID.fromString(string(message, "markerId", ""));
                        MarkerPayload marker = payload(message, MarkerPayload.class);
                        onClient(() -> {
                            MapMarkerStore.Marker existing =
                                    MapMarkerStore.find(markerId).orElse(null);
                            if (existing == null) {
                                throw new IllegalArgumentException("Marker not found");
                            }
                            backend.updateMarker(
                                    markerId,
                                    marker.x() == null ? existing.x : marker.x(),
                                    marker.y() == null ? existing.y : marker.y(),
                                    marker.z() == null ? existing.z : marker.z(),
                                    marker.name() == null ? existing.name : marker.name(),
                                    marker.icon() == null ? existing.icon : icon(marker.icon()),
                                    marker.primaryColor() == null
                                            ? existing.primary() : marker.primaryColor(),
                                    marker.accentColor() == null
                                            ? existing.accent() : marker.accentColor(),
                                    marker.lineColor() == null
                                            ? existing.line() : marker.lineColor(),
                                    marker.description() == null
                                            ? existing.description : marker.description());
                            return null;
                        });
                        pushMarkersIfChanged();
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "marker_delete" -> {
                        UUID markerId = UUID.fromString(string(message, "markerId", ""));
                        onClient(() -> {
                            backend.deleteMarker(markerId);
                            return null;
                        });
                        pushMarkersIfChanged();
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "marker_reset" -> {
                        UUID markerId = UUID.fromString(string(message, "markerId", ""));
                        onClient(() -> {
                            backend.resetAutomaticMarker(markerId);
                            return null;
                        });
                        pushMarkersIfChanged();
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "request_state" -> {
                        sendState();
                        respond(id, true, Map.of("ok", true), null);
                    }
                    case "ping" -> sendJson(Map.of("type", "pong"));
                    default -> respond(id, false, null, "Unknown message type: " + type);
                }
            } catch (Exception failure) {
                String error = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                respond(id, false, null, error);
            }
        }

        private <T> T payload(JsonObject message, Class<T> type) {
            if (!message.has("payload") || message.get("payload").isJsonNull()) {
                throw new IllegalArgumentException("Missing payload");
            }
            return GSON.fromJson(message.get("payload"), type);
        }

        private void handleViewport(JsonObject message) {
            ViewportPayload viewport = payload(message, ViewportPayload.class);
            ViewportSelection selection = onClient(() -> {
                double zoom = Math.max(0.03125, viewport.zoom());
                int width = Math.max(1, viewport.width());
                int height = Math.max(1, viewport.height());
                backendRuntime.updateViewport(FRONTEND_SESSION,
                        new CartographBackendApi.Viewport(
                                viewport.centerX(),
                                viewport.centerZ(),
                                zoom,
                                width,
                                height,
                                false),
                        false);

                String dimension = backend.dimension();
                if (dimension == null || dimension.isBlank()) {
                    return new ViewportSelection(
                            "", 0, -1, 0, -1, 0, 0, List.of());
                }

                double halfBlocksX = width / (2.0 * zoom);
                double halfBlocksZ = height / (2.0 * zoom);
                int minChunkX = Math.floorDiv(
                        (int) Math.floor(viewport.centerX() - halfBlocksX), 16) - 1;
                int maxChunkX = Math.floorDiv(
                        (int) Math.floor(viewport.centerX() + halfBlocksX), 16) + 1;
                int minChunkZ = Math.floorDiv(
                        (int) Math.floor(viewport.centerZ() - halfBlocksZ), 16) - 1;
                int maxChunkZ = Math.floorDiv(
                        (int) Math.floor(viewport.centerZ() + halfBlocksZ), 16) + 1;
                int centerChunkX = Math.floorDiv(
                        (int) Math.floor(viewport.centerX()), 16);
                int centerChunkZ = Math.floorDiv(
                        (int) Math.floor(viewport.centerZ()), 16);

                List<ChunkKey> cached = new ArrayList<>(
                        backend.cachedKeysInBounds(
                                dimension,
                                minChunkX, maxChunkX,
                                minChunkZ, maxChunkZ));
                cached.sort(Comparator.comparingLong(key -> {
                    long dx = Math.abs((long) key.x() - centerChunkX);
                    long dz = Math.abs((long) key.z() - centerChunkZ);
                    return Math.max(dx, dz);
                }));
                return new ViewportSelection(
                        dimension,
                        minChunkX, maxChunkX,
                        minChunkZ, maxChunkZ,
                        centerChunkX, centerChunkZ,
                        cached);
            });

            desiredDimension = selection.dimension();
            desiredMinChunkX = selection.minChunkX();
            desiredMaxChunkX = selection.maxChunkX();
            desiredMinChunkZ = selection.minChunkZ();
            desiredMaxChunkZ = selection.maxChunkZ();
            viewportTiles.clear();
            dirtyTiles.clear();
            dirtyQueued.clear();
            viewportTiles.addAll(selection.cachedKeys());

            drainViewportTiles();
            respond(
                    message.get("id"),
                    true,
                    Map.of(
                            "cachedVisible", selection.cachedKeys().size(),
                            "minChunkX", selection.minChunkX(),
                            "maxChunkX", selection.maxChunkX(),
                            "minChunkZ", selection.minChunkZ(),
                            "maxChunkZ", selection.maxChunkZ()),
                    null);
        }

        private boolean isDesired(ChunkKey key) {
            return key != null
                    && desiredDimension.equals(key.dimension())
                    && key.x() >= desiredMinChunkX
                    && key.x() <= desiredMaxChunkX
                    && key.z() >= desiredMinChunkZ
                    && key.z() <= desiredMaxChunkZ;
        }

        private void drainViewportTiles() {
            if (viewportTiles.isEmpty()) return;
            List<TileBatchItem> items = new ArrayList<>(512);
            while (items.size() < 512) {
                ChunkKey key = viewportTiles.poll();
                if (key == null) break;
                if (!isDesired(key)) continue;
                items.add(new TileBatchItem(
                        key.x(), key.z(),
                        browserEtags.getOrDefault(key, "")));
            }
            if (items.isEmpty()) return;

            try {
                TileBatchResult result =
                        onClient(() -> tileBatchForSocket(new TileBatchPayload(items)));
                updateBrowserEtags(result.etags());
                sendBinary(result.bytes());
            } catch (RuntimeException failure) {
                LOG.debug("Unable to publish Cartograph viewport tiles", failure);
                for (TileBatchItem item : items) {
                    ChunkKey key = new ChunkKey(
                            desiredDimension, item.x(), item.z());
                    if (isDesired(key)) viewportTiles.add(key);
                }
            }
        }

        private void queueTile(ChunkKey key) {
            if (open && isDesired(key) && dirtyQueued.add(key)) {
                dirtyTiles.add(key);
            }
        }

        private void drainDirtyTiles() {
            if (dirtyTiles.isEmpty()) return;
            List<TileBatchItem> items = new ArrayList<>(96);
            List<ChunkKey> keys = new ArrayList<>(96);
            while (items.size() < 96) {
                ChunkKey key = dirtyTiles.poll();
                if (key == null) break;
                dirtyQueued.remove(key);
                if (!isDesired(key)) continue;
                keys.add(key);
                items.add(new TileBatchItem(
                        key.x(), key.z(),
                        browserEtags.getOrDefault(key, "")));
            }
            if (items.isEmpty()) return;
            try {
                TileBatchResult result =
                        onClient(() -> tileBatchForSocket(new TileBatchPayload(items)));
                updateBrowserEtags(result.etags());
                sendBinary(result.bytes());
                pushMarkersIfChanged();
            } catch (RuntimeException failure) {
                LOG.debug("Unable to publish changed Cartograph tiles", failure);
                for (ChunkKey key : keys) {
                    if (isDesired(key) && dirtyQueued.add(key)) dirtyTiles.add(key);
                }
            }
        }

        private void updateBrowserEtags(Map<ChunkKey, String> etags) {
            etags.forEach(browserEtags::put);
        }

        private void publishLiveIfChanged() {
            long now = System.currentTimeMillis();
            if (now < nextLiveAt) return;
            nextLiveAt = now + 250L;
            Map<String, Object> live;
            try {
                live = onClient(CartographWebUiServer.this::liveSnapshot);
            } catch (RuntimeException failure) {
                LOG.debug("Unable to publish Cartograph live state", failure);
                return;
            }
            String nextWorldKey = worldKey(live);
            if (!nextWorldKey.equals(lastWorldKey)) {
                browserEtags.clear();
                viewportTiles.clear();
                dirtyTiles.clear();
                dirtyQueued.clear();
                desiredDimension = "";
                sendState();
                lastWorldKey = nextWorldKey;
                lastLiveJson = GSON.toJson(live);
                return;
            }
            if (lastStructurePresetRevision != CartographSettings.structurePresetRevision()) {
                pushMarkersIfChanged();
            }
            String serialized = GSON.toJson(live);
            if (!serialized.equals(lastLiveJson)) {
                sendJson(Map.of("type", "live", "data", live));
                lastLiveJson = serialized;
            }
        }

        private void sendState() {
            Map<String, Object> state =
                    onClient(CartographWebUiServer.this::stateSnapshot);
            sendJson(Map.of("type", "state", "data", state));
            lastWorldKey = worldKey(state);
            rememberMarkerState(state);
        }

        private void rememberMarkerState(Map<String, Object> state) {
            lastMarkersSignature = markersSignature(state);
            lastStructurePresetRevision = (long) state.get("structurePresetRevision");
        }

        private void pushMarkersIfChanged() {
            Map<String, Object> state =
                    onClient(CartographWebUiServer.this::stateSnapshot);
            String previous = lastMarkersSignature;
            rememberMarkerState(state);
            if (lastMarkersSignature.equals(previous)) return;
            Map<String, Object> data = new HashMap<>();
            data.put("markers", state.get("markers"));
            data.put("structurePresets", state.get("structurePresets"));
            sendJson(Map.of("type", "markers", "data", data));
        }

        private String markersSignature(Map<String, Object> state) {
            return GSON.toJson(state.get("markers"))
                    + GSON.toJson(state.get("structurePresets"));
        }

        private String worldKey(Map<String, Object> state) {
            return String.valueOf(state.get("worldId")) + "|"
                    + String.valueOf(state.get("dimension"));
        }

        private void respond(
                com.google.gson.JsonElement id,
                boolean ok,
                Object data,
                String error) {
            Map<String, Object> response = new HashMap<>();
            response.put("type", "response");
            if (id != null && !id.isJsonNull()) response.put("id", id);
            response.put("ok", ok);
            if (data != null) response.put("data", data);
            if (error != null) response.put("error", error);
            sendJson(response);
        }

        private void sendJson(Object value) {
            sendFrame(1, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
        }

        private void sendBinary(byte[] value) {
            sendFrame(2, value);
        }

        private void sendCloseFrame() {
            if (!open) return;
            open = false;
            sendFrame(8, new byte[]{(byte) 0x03, (byte) 0xe8});
        }

        private void sendFrame(int opcode, byte[] payload) {
            if (socket.isClosed()) return;
            try {
                int length = payload.length;
                output.write(0x80 | (opcode & 0x0f));
                if (length <= 125) {
                    output.write(length);
                } else if (length <= 65535) {
                    output.write(126);
                    output.write((length >>> 8) & 0xff);
                    output.write(length & 0xff);
                } else {
                    output.write(127);
                    long longLength = length;
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        output.write((int) ((longLength >>> shift) & 0xff));
                    }
                }
                output.write(payload);
                output.flush();
            } catch (IOException failure) {
                open = false;
            }
        }
    }

    private static WebSocketFrame readWebSocketFrame(
            Socket socket,
            BufferedInputStream input) throws IOException {
        socket.setSoTimeout(250);
        int first;
        try {
            first = input.read();
        } catch (SocketTimeoutException timeout) {
            return new WebSocketFrame(-1, new byte[0]);
        }
        if (first < 0) return null;

        socket.setSoTimeout(10_000);
        int second = input.read();
        if (second < 0) throw new IOException("Unexpected EOF in WebSocket frame");
        if ((second & 0x80) == 0) {
            throw new IOException("Client WebSocket frames must be masked");
        }
        long length = second & 0x7f;
        if (length == 126) {
            int a = input.read();
            int b = input.read();
            if (a < 0 || b < 0) throw new IOException("Truncated WebSocket frame length");
            length = ((a & 0xff) << 8) | (b & 0xff);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                int value = input.read();
                if (value < 0) throw new IOException("Truncated WebSocket frame length");
                length = (length << 8) | (value & 0xff);
            }
        }
        if (length < 0 || length > MAX_BODY) {
            throw new IOException("WebSocket message exceeds limit");
        }
        byte[] mask = input.readNBytes(4);
        if (mask.length != 4) throw new IOException("Truncated WebSocket mask");
        byte[] payload = input.readNBytes((int) length);
        if (payload.length != (int) length) {
            throw new IOException("Truncated WebSocket payload");
        }
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        socket.setSoTimeout(250);
        int opcode = first & 0x0f;
        if ((first & 0x80) == 0) {
            throw new IOException("Fragmented WebSocket messages are not supported");
        }
        return new WebSocketFrame(opcode, payload);
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsString();
    }

    private static int integer(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return object.get(key).getAsInt();
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
            backendRuntime.release(FRONTEND_SESSION);
            backendOpen = false;
        }
        restorePauseOnLostFocus();
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {}
            server = null;
        }
        clients.shutdownNow();
    }
}
