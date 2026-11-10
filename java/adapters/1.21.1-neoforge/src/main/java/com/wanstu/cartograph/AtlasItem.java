package com.wanstu.cartograph;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import java.util.List;

/**
 * First playable interaction: use the atlas in the main hand and put one
 * refill/compass in the offhand, then right click. Future GUI will use the
 * same immutable data component and service functions.
 */
public final class AtlasItem extends Item {
    public AtlasItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // Right-click also opens the atlas when it is held in the offhand.
        if (hand == InteractionHand.OFF_HAND) {
            if (level.isClientSide) CartographClient.open();
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }
        ItemStack atlas = player.getItemInHand(hand);
        ItemStack input = player.getOffhandItem();
        boolean material = input.is(Items.COMPASS) || input.is(Items.INK_SAC) || input.is(Items.GLOW_INK_SAC)
                || input.is(Items.RED_DYE) || input.is(Items.YELLOW_DYE) || input.is(Items.BLUE_DYE);
        if (!material && !player.isShiftKeyDown()) {
            if (level.isClientSide) CartographClient.open();
            return InteractionResultHolder.sidedSuccess(atlas, level.isClientSide);
        }
        if (!level.isClientSide) {
            AtlasWorldData data = AtlasWorldData.get((ServerLevel) level);
            AtlasWorldData.Entry entry = data.access(atlas);
            AtlasState current = entry.state();
            AtlasState next = current;
            String message;
            boolean consume = false;
            if (player.isShiftKeyDown() && current.compassInstalled() && input.isEmpty()) {
                next = current.withCompass(false);
                ItemStack returned = new ItemStack(Items.COMPASS);
                if (!player.getInventory().add(returned)) player.drop(returned, false);
                message = "cartograph.message.compass_removed";
            } else if (input.is(Items.COMPASS)) {
                if (!current.compassInstalled()) {
                    next = current.withCompass(true);
                    consume = true;
                    message = "cartograph.message.compass_installed";
                } else message = "cartograph.message.compass_present";
            } else if (input.is(Items.INK_SAC)) {
                if (!(current.inkEnabled() && CartographConfig.permanent(0)))
                    next = current.fillInkUnits(CartographConfig.refillUnits(0));
                consume = true;
                message = "cartograph.message.ink_filled";
            } else if (input.is(Items.GLOW_INK_SAC)) {
                if (!(current.glowEnabled() && CartographConfig.permanent(2)))
                    next = current.fillGlowUnits(CartographConfig.refillUnits(2));
                consume = true;
                message = "cartograph.message.glow_filled";
            } else if (input.is(Items.RED_DYE) || input.is(Items.YELLOW_DYE) || input.is(Items.BLUE_DYE)) {
                AtlasState.Primary primary = input.is(Items.RED_DYE) ? AtlasState.Primary.RED :
                        input.is(Items.YELLOW_DYE) ? AtlasState.Primary.YELLOW : AtlasState.Primary.BLUE;
                if (!(current.colorEnabled() && CartographConfig.permanent(1)))
                    next = current.addDye(primary, CartographConfig.refillUnits(1));
                consume = true;
                message = next.colorEnabled() ? "cartograph.message.dye_installed" : "cartograph.message.dye_waiting";
            } else message = "cartograph.message.inspect";

            data.update(entry, next, atlas);
            if (consume && !next.equals(current) && !player.getAbilities().instabuild) input.shrink(1);
            player.displayClientMessage(Component.translatable(message,
                    next.pages(), next.inkUnits() / 10.0, next.pigmentUnits() / 10.0,
                    next.glowUnits() / 10.0).withStyle(ChatFormatting.GOLD), true);
        }
        return InteractionResultHolder.sidedSuccess(atlas, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        AtlasState state = stack.getOrDefault(CartographMod.ATLAS_STATE.get(), AtlasState.blank());
        tooltip.add(Component.translatable("cartograph.tooltip.pages", state.pages()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("cartograph.tooltip.resources",
                state.inkUnits() / 10.0, state.pigmentUnits() / 10.0,
                state.glowUnits() / 10.0).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("cartograph.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
