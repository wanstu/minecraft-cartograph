package com.wanstu.cartographv3;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

final class WorldIdentity {
    private static final Map<MinecraftServer, UUID> IDS = new WeakHashMap<>();

    private WorldIdentity() {}

    static synchronized UUID get(MinecraftServer server) {
        UUID cached = IDS.get(server);
        if (cached != null) return cached;

        Path dir = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("cartograph_v3");
        Path file = dir.resolve("world.id");
        try {
            Files.createDirectories(dir);
            UUID id;
            if (Files.isRegularFile(file)) {
                id = UUID.fromString(Files.readString(file, StandardCharsets.US_ASCII).trim());
            } else {
                id = UUID.randomUUID();
                Path tmp = dir.resolve("world.id.tmp-" + UUID.randomUUID());
                Files.writeString(tmp, id.toString() + "\n", StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(tmp, file);
                }
            }
            IDS.put(server, id);
            return id;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Cannot establish Cartograph V3 world identity", failure);
        }
    }

    static synchronized void clear(MinecraftServer server) {
        IDS.remove(server);
    }
}
