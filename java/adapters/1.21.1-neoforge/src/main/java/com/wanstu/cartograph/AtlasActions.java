package com.wanstu.cartograph;

import com.wanstu.cartograph.core.SurveyLedger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Only the server mutates map markers, consumables or the nonconsumable compass.
 * Inputs are strictly enumerated and validated against a possessed atlas,
 * the bound dimension, purchased page and actually surveyed chunk.
 */
public final class AtlasActions {
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private AtlasActions() {}

    private static boolean allowed(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        Long prior = LAST_ACTION.get(player.getUUID());
        if (prior != null && now >= prior && now - prior < 3) return false;
        LAST_ACTION.put(player.getUUID(), now);
        if (LAST_ACTION.size() > 512) LAST_ACTION.entrySet().removeIf(e ->
                player.server.getPlayerList().getPlayer(e.getKey()) == null);
        return true;
    }

    private static ItemStack findIngredient(ServerPlayer player, Item item) {
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) return stack;
        if (player.getOffhandItem().is(item)) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    public static void refill(ServerPlayer player, MapNetwork.Refill req) {
        if (req.material() < 0 || req.material() > 5 || !allowed(player)) return;
        ItemStack atlas = AtlasMapServer.findAtlas(player, req.atlasId());
        if (atlas.isEmpty()) return;
        AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
        AtlasWorldData.Entry entry = data.access(atlas);
        AtlasState state = entry.state();
        int slot = req.material();

        if (slot == 5 && state.compassInstalled()) {
            data.update(entry, state.withCompass(false), atlas);
            ItemStack returned = new ItemStack(Items.COMPASS);
            if (!player.getInventory().add(returned)) player.drop(returned, false);
            player.displayClientMessage(Component.translatable("cartograph.message.gui_compass_removed"), true);
            return;
        }

        Item material = switch (slot) {
            case 0 -> Items.INK_SAC;
            case 1 -> Items.GLOW_INK_SAC;
            case 2 -> Items.RED_DYE;
            case 3 -> Items.YELLOW_DYE;
            case 4 -> Items.BLUE_DYE;
            case 5 -> Items.COMPASS;
            default -> Items.AIR;
        };
        ItemStack ingredient = findIngredient(player, material);
        if (ingredient.isEmpty() && !player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("cartograph.message.material_missing"), true);
            return;
        }
        AtlasState next = switch (slot) {
            case 0 -> state.fillInk(CartographConfig.INK_REFILL.get());
            case 1 -> state.fillGlow(CartographConfig.GLOW_REFILL.get());
            case 2 -> state.addDye(AtlasState.Primary.RED, CartographConfig.PIGMENT_REFILL.get());
            case 3 -> state.addDye(AtlasState.Primary.YELLOW, CartographConfig.PIGMENT_REFILL.get());
            case 4 -> state.addDye(AtlasState.Primary.BLUE, CartographConfig.PIGMENT_REFILL.get());
            case 5 -> state.withCompass(true);
            default -> state;
        };
        if (next.equals(state)) return;
        if (!player.getAbilities().instabuild) ingredient.shrink(1);
        data.update(entry, next, atlas);
        player.displayClientMessage(Component.translatable(
                slot == 5 ? "cartograph.message.gui_compass_installed"
                        : "cartograph.message.gui_refilled"), true);
    }

    public static boolean mayConfigure(ServerPlayer player) {
        return player.hasPermissions(2)
                || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    public static MapNetwork.CostSettings settings(ServerPlayer player) {
        return new MapNetwork.CostSettings(
                CartographConfig.inkCostUnits(), CartographConfig.pigmentCostUnits(),
                CartographConfig.glowCostUnits(), CartographConfig.INK_REFILL.get(),
                CartographConfig.PIGMENT_REFILL.get(), CartographConfig.GLOW_REFILL.get(),
                mayConfigure(player));
    }

    public static void updateCosts(ServerPlayer player, MapNetwork.SetCostSettings request) {
        if (!mayConfigure(player) || !allowed(player)) return;
        if (request.ink() < 0 || request.ink() > 100
                || request.pigment() < 0 || request.pigment() > 100
                || request.glow() < 0 || request.glow() > 100
                || request.inkRefill() < 1 || request.inkRefill() > 10000
                || request.pigmentRefill() < 1 || request.pigmentRefill() > 10000
                || request.glowRefill() < 1 || request.glowRefill() > 10000) return;
        CartographConfig.INK_MULTIPLIER.set(request.ink() / 10.0);
        CartographConfig.PIGMENT_MULTIPLIER.set(request.pigment() / 10.0);
        CartographConfig.GLOW_MULTIPLIER.set(request.glow() / 10.0);
        CartographConfig.INK_REFILL.set(request.inkRefill());
        CartographConfig.PIGMENT_REFILL.set(request.pigmentRefill());
        CartographConfig.GLOW_REFILL.set(request.glowRefill());
        CartographConfig.INK_MULTIPLIER.save();
        CartographConfig.PIGMENT_MULTIPLIER.save();
        CartographConfig.GLOW_MULTIPLIER.save();
        CartographConfig.INK_REFILL.save();
        CartographConfig.PIGMENT_REFILL.save();
        CartographConfig.GLOW_REFILL.save();
        PacketDistributor.sendToPlayer(player, settings(player));
    }

    public static void exportMap(ServerPlayer player, MapNetwork.ExportMap request) {
        AtlasExports.export(player, request);
    }

    public static void editMarker(ServerPlayer player, MapNetwork.EditMarker req) {
        if (!allowed(player)) return;
        ItemStack atlas = AtlasMapServer.findAtlas(player, req.atlasId());
        if (atlas.isEmpty()) return;
        if (Math.abs((long) req.x()) > 30_000_000 || Math.abs((long) req.z()) > 30_000_000)
            return;
        AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
        AtlasWorldData.Entry entry = data.access(atlas);
        String dimension = player.serverLevel().dimension().location().toString();

        String targetId = req.id();
        AtlasMarker existing = targetId.isBlank() ? null : entry.getMarker(targetId);
        if (!targetId.isBlank() && existing == null) return;
        if (existing != null && (!existing.dimension().equals(dimension)
                || existing.x() != req.x() || existing.z() != req.z())) return;

        int pageX = Math.floorDiv(req.x(), 128), pageZ = Math.floorDiv(req.z(), 128);
        if (!entry.isCovered(dimension, pageX, pageZ)) return;

        if (req.delete()) {
            if (existing != null) data.deleteMarker(entry, existing.id());
            return;
        }

        if (existing == null) {
            if (entry.markerCount() >= 1024 || entry.markerCountOnPage(dimension, pageX, pageZ) >= 64)
                return;
            var key = new SurveyLedger.ChunkKey(dimension,
                    Math.floorDiv(req.x(), 16), Math.floorDiv(req.z(), 16));
            if (!entry.paid(key)) return; // no labels over undiscovered / unpurchased data
        }

        AtlasState current = entry.state();
        boolean wasPaid = existing != null && existing.glowEverPaid();
        if (req.glow() && !wasPaid) {
            AtlasState paid = current.payGlow(CartographConfig.glowCostUnits());
            if (paid == null) {
                player.displayClientMessage(Component.translatable("cartograph.message.glow_needed"), true);
                return;
            }
            data.update(entry, paid, atlas);
            wasPaid = true;
        }
        int color = current.colorEnabled() ? req.color() : 0;
        AtlasMarker updated = new AtlasMarker(existing == null ? UUID.randomUUID().toString() : existing.id(),
                dimension, req.x(), req.z(), req.name(), req.icon(), color, req.glow(), wasPaid);
        data.putMarker(entry, updated, atlas);
    }
}
