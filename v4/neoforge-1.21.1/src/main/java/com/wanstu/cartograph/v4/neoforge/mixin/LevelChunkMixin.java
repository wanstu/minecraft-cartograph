package com.wanstu.cartograph.v4.neoforge.mixin;
import com.wanstu.cartograph.v4.neoforge.CartographV4Dev;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void cartographV4$changed(BlockPos pos,BlockState state,boolean moving,CallbackInfoReturnable<BlockState> result){
        if(result.getReturnValue()!=null&&result.getReturnValue()!=state)
            CartographV4Dev.changed((LevelChunk)(Object)this,pos);
    }
}
