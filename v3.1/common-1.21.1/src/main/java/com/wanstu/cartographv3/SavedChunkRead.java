package com.wanstu.cartographv3;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

final class SavedChunkRead {
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();
    private static final ExecutorService DISK = Executors.newFixedThreadPool(8, task -> {
        Thread thread = new Thread(task, "cartograph-v3-mca-" + THREAD_COUNT.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private CompletableFuture<Optional<CompoundTag>> result;

    void whenRead(BiConsumer<Optional<CompoundTag>, Throwable> callback) {
        if (result == null) result = new CompletableFuture<>();
        result.whenComplete(callback);
    }

    // Register both consumers before submitting so decoding always runs on the disk worker.
    void submit(ServerLevel level, int chunkX, int chunkZ) {
        if (result == null) return;

        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path region = DimensionType.getStorageFolder(level.dimension(), root).resolve("region");
        var completion = result;
        DISK.execute(() -> {
            try {
                completion.complete(RegionNbtReader.readChunk(region, chunkX, chunkZ));
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
    }
}
