package com.wanstu.cartograph;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * A custom 2-ingredient shapeless recipe is essential: vanilla JSON recipes
 * would recreate a blank book and destroy all stored state.
 */
public final class AtlasExpandRecipe extends CustomRecipe {
    public AtlasExpandRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        int atlases = 0, maps = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.is(CartographMod.ATLAS.get())) {
                if (++atlases > 1) return false;
            } else if (stack.is(Items.MAP)) {
                if (++maps > 1) return false;
            } else return false;
        }
        if (atlases != 1 || maps != 1) return false;
        for (int i = 0; i < input.size(); i++) {
            ItemStack s = input.getItem(i);
            if (s.is(CartographMod.ATLAS.get())) {
                return s.getOrDefault(CartographMod.ATLAS_STATE.get(), AtlasState.blank()).pages() < AtlasState.MAX_PAGES;
            }
        }
        return false;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        for (int i = 0; i < input.size(); i++) {
            ItemStack source = input.getItem(i);
            if (source.is(CartographMod.ATLAS.get())) {
                ItemStack result = source.copy();
                result.setCount(1);
                AtlasState current = source.getOrDefault(CartographMod.ATLAS_STATE.get(), AtlasState.blank());
                result.set(CartographMod.ATLAS_STATE.get(), current.identified().extendOnePage());
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }

    @Override
    public RecipeSerializer<?> getSerializer() { return CartographMod.ATLAS_EXPAND.get(); }
}
