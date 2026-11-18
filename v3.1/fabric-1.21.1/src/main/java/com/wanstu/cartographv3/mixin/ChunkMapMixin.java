package com.wanstu.cartographv3.mixin;

import com.wanstu.cartographv3.CartographServerEvents;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    // Match NeoForge's ChunkDataEvent.Save point after serialization.
    @Inject(method = "save", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;"))
    private void cartograph$onChunkSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> result) {
        CartographServerEvents.chunkSaved(level, chunk.getPos());
    }
}
