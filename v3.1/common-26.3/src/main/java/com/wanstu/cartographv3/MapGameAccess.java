package com.wanstu.cartographv3;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Set;

final class MapGameAccess {
    static final String FACTS_DIRECTORY = "cartograph_v31_map_facts_26_3";

    private MapGameAccess() {}

    static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    static Identifier parseId(String value) { return Identifier.tryParse(value); }

    static String dimension(Level level) { return level.dimension().identifier().toString(); }
    static ServerLevel level(ServerPlayer player) { return player.level(); }
    static MinecraftServer server(ServerPlayer player) { return player.level().getServer(); }
    static int minY(LevelHeightAccessor level) { return level.getMinY(); }
    static int maxYExclusive(LevelHeightAccessor level) { return level.getMaxY() + 1; }
    static int chunkX(ChunkPos pos) { return pos.x(); }
    static int chunkZ(ChunkPos pos) { return pos.z(); }

    static Path regionDirectory(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Identifier dimension = level.dimension().identifier();
        return root.resolve("dimensions").resolve(dimension.getNamespace()).resolve(dimension.getPath()).resolve("region");
    }

    static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
        return access.lookupOrThrow(key);
    }

    static Biome biome(Registry<Biome> registry, String name) {
        var id = parseId(name);
        return id == null ? null : registry.getValue(id);
    }

    static int shade(MapColor color, MapColor.Brightness brightness) {
        return color.calculateARGBColor(brightness);
    }

    static boolean canTeleport(ServerPlayer player) {
        return server(player).isSingleplayerOwner(new NameAndId(player.getGameProfile()))
                || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        player.teleportTo(level, x, y, z, Set.of(), player.getYRot(), player.getXRot(), true);
    }
}
