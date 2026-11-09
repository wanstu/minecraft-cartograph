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

    public static void reply(ServerPlayer player, boolean ok, String message) {
        PacketDistributor.sendToPlayer(player, new MapNetwork.Feedback(ok, message));
    }

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
        if (req.material() < 0 || req.material() > 5) {
            reply(player, false, "未知耗材类型");
            return;
        }
        if (!allowed(player)) { reply(player, false, "操作过快，请稍后再试"); return; }
        ItemStack atlas = AtlasMapServer.findAtlas(player, req.atlasId());
        if (atlas.isEmpty()) { reply(player, false, "背包中未找到对应地图册"); return; }
        AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
        AtlasWorldData.Entry entry = data.access(atlas);
        AtlasState state = entry.state();
        int slot = req.material();

        if (slot == 5 && state.compassInstalled()) {
            data.update(entry, state.withCompass(false), atlas);
            ItemStack returned = new ItemStack(Items.COMPASS);
            if (!player.getInventory().add(returned)) player.drop(returned, false);
            reply(player, true, "已取出指南针，玩家位置显示关闭");
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
            reply(player, false, "背包里没有所选材料；请先放入对应墨囊或染料");
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
        if (next.equals(state)) { reply(player, false, "地图册资源已满或状态未变化"); return; }
        if (!player.getAbilities().instabuild) ingredient.shrink(1);
        data.update(entry, next, atlas);
        reply(player, true, slot == 5 ? "指南针已装入，玩家位置显示开启" :
                "已投入材料，地图册资源已更新");
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
        if (!mayConfigure(player)) { reply(player, false, "没有权限修改服务器设置"); return; }
        if (!allowed(player)) { reply(player, false, "操作过快，请稍后再试"); return; }
        if (request.ink() < 0 || request.ink() > 100
                || request.pigment() < 0 || request.pigment() > 100
                || request.glow() < 0 || request.glow() > 100
                || request.inkRefill() < 1 || request.inkRefill() > 10000
                || request.pigmentRefill() < 1 || request.pigmentRefill() > 10000
                || request.glowRefill() < 1 || request.glowRefill() > 10000) {
            reply(player, false, "设置超出范围，请使用 0–10 倍和 1–10000 资源量");
            return;
        }
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
        reply(player, true, "服务端设置保存成功");
    }

    public static void exportMap(ServerPlayer player, MapNetwork.ExportMap request) {
        AtlasExports.export(player, request);
    }

    public static void editMarker(ServerPlayer player, MapNetwork.EditMarker req) {
        if (!allowed(player)) { reply(player, false, "操作过快，请稍后再试"); return; }
        ItemStack atlas = AtlasMapServer.findAtlas(player, req.atlasId());
        if (atlas.isEmpty()) { reply(player, false, "背包中未找到对应地图册"); return; }
        if (Math.abs((long) req.x()) > 30_000_000 || Math.abs((long) req.z()) > 30_000_000) {
            reply(player, false, "标点坐标超出世界范围");
            return;
        }
        AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
        AtlasWorldData.Entry entry = data.access(atlas);
        String dimension = player.serverLevel().dimension().location().toString();

        String targetId = req.id();
        AtlasMarker existing = targetId.isBlank() ? null : entry.getMarker(targetId);
        if (!targetId.isBlank() && existing == null) {
            reply(player, false, "标点已不存在，可能已被其他操作删除"); return;
        }
        if (existing != null && (!existing.dimension().equals(dimension)
                || existing.x() != req.x() || existing.z() != req.z())) {
            reply(player, false, "不能修改其他维度或位置的标点"); return;
        }

        int pageX = Math.floorDiv(req.x(), 128), pageZ = Math.floorDiv(req.z(), 128);
        if (!entry.isCovered(dimension, pageX, pageZ)) {
            reply(player, false, "该位置未购买地图页；工作台用空地图扩充"); return;
        }

        if (req.delete()) {
            if (existing != null) data.deleteMarker(entry, existing.id());
            reply(player, true, existing != null ? "标点已删除" : "标点已不存在");
            return;
        }

        if (existing == null) {
            if (entry.markerCount() >= 1024 || entry.markerCountOnPage(dimension, pageX, pageZ) >= 64) {
                reply(player, false, "已达到标点数量上限"); return;
            }
            var key = new SurveyLedger.ChunkKey(dimension,
                    Math.floorDiv(req.x(), 16), Math.floorDiv(req.z(), 16));
            if (!entry.paid(key)) {
                reply(player, false, "这处区块尚未完成首次测绘，不能创建标点");
                return;
            } // no labels over undiscovered / unpurchased data
        }

        AtlasState current = entry.state();
        boolean wasPaid = existing != null && existing.glowEverPaid();
        if (req.glow() && !wasPaid) {
            AtlasState paid = current.payGlow(CartographConfig.glowCostUnits());
            if (paid == null) {
                reply(player, false, "发光墨水不足，请先填入发光墨囊");
                return;
            }
            data.update(entry, paid, atlas);
            wasPaid = true;
        }
        int color = current.colorEnabled() ? req.color() : 0;
        AtlasMarker updated = new AtlasMarker(existing == null ? UUID.randomUUID().toString() : existing.id(),
                dimension, req.x(), req.z(), req.name(), req.icon(), color, req.glow(), wasPaid);
        data.putMarker(entry, updated, atlas);
        reply(player, true, existing == null ? "新标点保存成功" : "标点修改成功");
    }
}
