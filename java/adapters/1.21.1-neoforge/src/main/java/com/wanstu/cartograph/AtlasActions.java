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
    private static final int OTHER_ACTIONS = 7;
    private static final Map<UUID, long[]> LAST_ACTION = new HashMap<>();
    private AtlasActions() {}

    public static void reply(ServerPlayer player, boolean ok, String message) {
        PacketDistributor.sendToPlayer(player, new MapNetwork.Feedback(ok, message));
    }

    private static boolean allowed(ServerPlayer player, int action) {
        long now = player.serverLevel().getGameTime() + 1;
        long[] timings = LAST_ACTION.computeIfAbsent(player.getUUID(), id -> new long[OTHER_ACTIONS + 1]);
        long prior = timings[action];
        if (prior != 0 && now >= prior && now - prior < 3) return false;
        timings[action] = now;
        if (LAST_ACTION.size() > 512) LAST_ACTION.entrySet().removeIf(e ->
                player.server.getPlayerList().getPlayer(e.getKey()) == null);
        return true;
    }

    private static ItemStack findIngredient(ServerPlayer player, Item item) {
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) return stack;
        if (player.getOffhandItem().is(item)) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    /** Expand a possessed atlas in place; ordinary maps are spent, never generated or predicted. */
    public static void addPage(ServerPlayer player, MapNetwork.AddPage req) {
        if (!allowed(player, OTHER_ACTIONS)) { reply(player, false, "操作过快，请稍后再试"); return; }
        ItemStack atlas = AtlasMapServer.findAtlas(player, req.atlasId());
        if (atlas.isEmpty()) { reply(player, false, "背包中未找到对应地图册"); return; }
        AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
        AtlasWorldData.Entry entry = data.access(atlas);
        AtlasState current = entry.state();
        if (current.pages() >= AtlasState.MAX_PAGES) {
            reply(player, false, "地图册已达到最大页数"); return;
        }
        AtlasState next = current;
        if (!player.getAbilities().instabuild && !CartographConfig.permanent(3)) {
            next = current.payPaper(CartographConfig.cost(3));
            if (next == null) { reply(player, false, "地图纸值不足，请先在材料槽投入空地图"); return; }
        } else if (!player.getAbilities().instabuild && !current.paperEnabled()) {
            reply(player, false, "请先投入一张原版空地图以解锁永久地图纸"); return;
        }
        AtlasState expanded = next.extendOnePage();
        data.update(entry, expanded, atlas);
        reply(player, true, "已扩充地图范围 · 当前 " + expanded.pages() + " 页");
    }

    public static void refill(ServerPlayer player, MapNetwork.Refill req) {
        if (req.material() < 0 || req.material() > 6) {
            reply(player, false, "未知耗材类型");
            return;
        }
        if (!allowed(player, req.material())) { reply(player, false, "操作过快，请稍后再试"); return; }
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

        if ((slot == 0 && state.inkEnabled() && CartographConfig.permanent(0))
                || (slot == 1 && state.glowEnabled() && CartographConfig.permanent(2))
                || (slot >= 2 && slot <= 4 && state.colorEnabled() && CartographConfig.permanent(1))
                || (slot == 6 && state.paperEnabled() && CartographConfig.permanent(3))) {
            reply(player, false, "该材料已永久解锁"); return;
        }
        Item material = switch (slot) {
            case 0 -> Items.INK_SAC;
            case 1 -> Items.GLOW_INK_SAC;
            case 2 -> Items.RED_DYE;
            case 3 -> Items.YELLOW_DYE;
            case 4 -> Items.BLUE_DYE;
            case 5 -> Items.COMPASS;
            case 6 -> Items.MAP;
            default -> Items.AIR;
        };
        ItemStack ingredient = findIngredient(player, material);
        if (ingredient.isEmpty() && !player.getAbilities().instabuild) {
            reply(player, false, "背包里没有所选材料；请先放入对应墨囊或染料");
            return;
        }
        AtlasState next = switch (slot) {
            case 0 -> state.fillInkUnits(CartographConfig.refillUnits(0));
            case 1 -> state.fillGlowUnits(CartographConfig.refillUnits(2));
            case 2 -> state.addDye(AtlasState.Primary.RED, CartographConfig.refillUnits(1));
            case 3 -> state.addDye(AtlasState.Primary.YELLOW, CartographConfig.refillUnits(1));
            case 4 -> state.addDye(AtlasState.Primary.BLUE, CartographConfig.refillUnits(1));
            case 5 -> state.withCompass(true);
            case 6 -> state.fillPaperUnits(CartographConfig.refillUnits(3));
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
        return new MapNetwork.CostSettings(CartographConfig.rates(), mayConfigure(player));
    }

    public static void updateCosts(ServerPlayer player, MapNetwork.SetCostSettings request) {
        if (!mayConfigure(player)) { reply(player, false, "没有权限修改服务器设置"); return; }
        if (!allowed(player, OTHER_ACTIONS)) { reply(player, false, "操作过快，请稍后再试"); return; }
        if (!request.rates().valid()) { reply(player, false, "倍率必须在 0.1–10.0 之间"); return; }
        CartographConfig.update(request.rates());
        PacketDistributor.sendToPlayer(player, settings(player));
        reply(player, true, "耗材设置已保存");
    }

    public static void listAnnotations(ServerPlayer player, String atlasId) {
        ItemStack atlas = AtlasMapServer.findAtlas(player, atlasId);
        if (atlas.isEmpty()) return;
        AtlasWorldData.Entry entry = AtlasWorldData.get(player.serverLevel()).access(atlas);
        String dim = player.serverLevel().dimension().location().toString();
        PacketDistributor.sendToPlayer(player, new MapNetwork.Annotations(entry.state().id(), dim, entry.regions(dim), entry.markers(dim)));
    }

    public static void editRegion(ServerPlayer player, MapNetwork.EditRegion req) {
        if (!allowed(player, OTHER_ACTIONS)) { reply(player, false, "操作过快，请稍后再试"); return; }
        ItemStack atlas = AtlasMapServer.findAtlas(player, req.atlasId());
        if (atlas.isEmpty()) { reply(player, false, "背包中未找到对应地图册"); return; }
        AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
        AtlasWorldData.Entry entry = data.access(atlas);
        String dim = player.serverLevel().dimension().location().toString();
        NamedRegion existing = req.regionId().isBlank() ? null : entry.getRegion(req.regionId());
        if (!req.regionId().isBlank() && (existing == null || !existing.dimension().equals(dim))) {
            reply(player, false, "区域已不存在或不在当前维度"); return;
        }
        if (req.delete()) {
            if (existing == null) { reply(player, false, "未找到要删除的区域"); return; }
            data.deleteRegion(entry, existing.id());
        } else {
            if (existing == null && entry.regionCount() >= NamedRegion.MAX_REGIONS) {
                reply(player, false, "已达到区域数量上限"); return;
            }
            for (NamedRegion.Point point : req.boundary()) {
                if (!entry.isCovered(dim, Math.floorDiv(point.x(), 128), Math.floorDiv(point.z(), 128))) {
                    reply(player, false, "区域顶点超出地图册覆盖范围"); return;
                }
            }
            try {
                NamedRegion region = existing == null
                        ? NamedRegion.create(dim, req.name(), req.regionType(), req.color(), req.note(), req.boundary())
                        : new NamedRegion(existing.id(), dim, req.name(), req.regionType(), req.color(), req.note(), req.boundary());
                data.putRegion(entry, region);
            } catch (IllegalArgumentException invalid) {
                reply(player, false, "请检查区域名称和边界，至少三个顶点且边界不能交叉"); return;
            }
        }
        listAnnotations(player, entry.state().id());
        reply(player, true, req.delete() ? "区域已删除" : "区域已保存");
    }

    public static void exportMap(ServerPlayer player, MapNetwork.ExportMap request) {
        AtlasExports.export(player, request);
    }

    public static void editMarker(ServerPlayer player, MapNetwork.EditMarker req) {
        if (!allowed(player, OTHER_ACTIONS)) { reply(player, false, "操作过快，请稍后再试"); return; }
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
                || req.delete() && (existing.x() != req.x() || existing.z() != req.z()))) {
            reply(player, false, "不能修改其他维度或位置的标点"); return;
        }

        int pageX = Math.floorDiv(req.x(), 128), pageZ = Math.floorDiv(req.z(), 128);
        if (!entry.isCovered(dimension, pageX, pageZ)) {
            reply(player, false, "该位置尚未覆盖；请在地图册面板使用地图纸值扩充"); return;
        }

        if (req.delete()) {
            if (existing != null) data.deleteMarker(entry, existing.id());
            listAnnotations(player, entry.state().id());
            reply(player, true, existing != null ? "标点已删除" : "标点已不存在");
            return;
        }

        if (existing == null || existing.x() != req.x() || existing.z() != req.z()) {
            if ((existing == null && entry.markerCount() >= AtlasMarker.MAX_MARKERS) || entry.markerCountOnPage(dimension, pageX, pageZ) >= 64) {
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
            AtlasState paid = current.payGlow(CartographConfig.permanent(2) ? 0 : CartographConfig.cost(2));
            if (paid == null) {
                reply(player, false, "发光墨水不足，请先填入发光墨囊");
                return;
            }
            data.update(entry, paid, atlas);
            wasPaid = true;
        }
        int color = req.color();
        AtlasMarker updated = new AtlasMarker(existing == null ? UUID.randomUUID().toString() : existing.id(),
                dimension, req.x(), req.z(), req.name(), req.icon(), color, req.glow(), wasPaid);
        data.putMarker(entry, updated, atlas);
        listAnnotations(player, entry.state().id());
        reply(player, true, existing == null ? "新标点保存成功" : "标点修改成功");
    }
}
