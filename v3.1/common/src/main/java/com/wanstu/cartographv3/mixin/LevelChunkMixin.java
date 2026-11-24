package com.wanstu.cartographv3.mixin;

import com.wanstu.cartographv3.CartographServerEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe successful actual block mutations, including fluids and other mods. */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void cartograph$surfaceChanged(CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null)
            CartographServerEvents.chunkChanged((LevelChunk) (Object) this);
    }
}
