package com.wanstu.cartographv3;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

final class MapGameAccess {
    static final String FACTS_DIRECTORY = "cartograph_v31_map_facts";

    private MapGameAccess() {}

    static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    static ResourceLocation parseId(String value) { return ResourceLocation.tryParse(value); }

    static String dimension(Level level) { return level.dimension().location().toString(); }
    static ServerLevel level(ServerPlayer player) { return player.serverLevel(); }
    static MinecraftServer server(ServerPlayer player) { return player.server; }
    static int minY(LevelHeightAccessor level) { return level.getMinBuildHeight(); }
    static int maxYExclusive(LevelHeightAccessor level) { return level.getMaxBuildHeight(); }
    static int chunkX(ChunkPos pos) { return pos.x; }
    static int chunkZ(ChunkPos pos) { return pos.z; }

    static Path regionDirectory(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("region");
    }

    static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
        return access.registryOrThrow(key);
    }

    static Biome biome(Registry<Biome> registry, String name) {
        var id = parseId(name);
        return id == null ? null : registry.get(id);
    }

    static int shade(MapColor color, MapColor.Brightness brightness) {
        return color.calculateRGBColor(brightness);
    }

    static boolean canTeleport(ServerPlayer player) {
        return player.server.isSingleplayerOwner(player.getGameProfile()) || player.hasPermissions(2);
    }

    static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
    }
}
