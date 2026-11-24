package com.wanstu.cartographv3.clientmixin;

import com.wanstu.cartographv3.MapWorldRenderPolicy;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GameRenderer.class)
abstract class MapWorldRenderMixin {
    @ModifyVariable(method = "extract", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean cartograph$visibleWorld(boolean renderLevel) {
        return MapWorldRenderPolicy.shouldRenderWorld(renderLevel);
    }
}
