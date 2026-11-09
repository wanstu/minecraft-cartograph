package com.wanstu.cartograph;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CartographMod.MOD_ID)
public final class CartographMod {
    public static final String MOD_ID = "cartograph";

    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<AtlasState>> ATLAS_STATE =
            COMPONENTS.registerComponentType("atlas_state", b -> b.persistent(AtlasState.CODEC)
                    .networkSynchronized(ByteBufCodecs.fromCodec(AtlasState.CODEC)));

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredItem<AtlasItem> ATLAS = ITEMS.registerItem("atlas",
            AtlasItem::new,
            new Item.Properties().stacksTo(1));

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD_ID);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<AtlasExpandRecipe>> ATLAS_EXPAND =
            SERIALIZERS.register("expand_atlas", () -> new SimpleCraftingRecipeSerializer<>(AtlasExpandRecipe::new));

    public CartographMod(IEventBus modBus, ModContainer container) {
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        SERIALIZERS.register(modBus);
        modBus.addListener(this::creativeTab);
        modBus.addListener(MapNetwork::register);
        NeoForge.EVENT_BUS.addListener(AtlasMapServer::tick);
        NeoForge.EVENT_BUS.addListener(AtlasExports::tick);

        container.registerConfig(ModConfig.Type.SERVER, CartographConfig.SPEC);
    }

    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(ATLAS);
    }
}
