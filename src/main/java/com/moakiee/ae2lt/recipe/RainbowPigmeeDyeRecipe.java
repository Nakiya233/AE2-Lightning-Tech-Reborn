package com.moakiee.ae2lt.recipe;

import com.google.gson.JsonObject;
import com.moakiee.ae2lt.registry.ModFumos;
import com.moakiee.ae2lt.registry.ModRecipeTypes;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

/** A visible shaped recipe that returns the Pigmee catalyst with its NBT intact. */
public final class RainbowPigmeeDyeRecipe extends ShapedRecipe {
    private RainbowPigmeeDyeRecipe(ShapedRecipe recipe) {
        super(recipe.getId(), recipe.getGroup(), recipe.category(), recipe.getWidth(), recipe.getHeight(),
                recipe.getIngredients(), recipe.getResultItem(null), recipe.showNotification());
    }

    @Override
    public boolean matches(CraftingContainer input, net.minecraft.world.level.Level level) {
        if (input.getWidth() != 3 || input.getHeight() != 3 || getWidth() != 3 || getHeight() != 3) return false;
        for (int slot = 0; slot < 9; slot++) if (!getIngredients().get(slot).test(input.getItem(slot))) return false;
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeTypes.RAINBOW_PIGMEE_DYE_SERIALIZER.get();
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer input) {
        var remaining = super.getRemainingItems(input);
        for (int slot = 0; slot < input.getContainerSize(); slot++) {
            var stack = input.getItem(slot);
            if (stack.is(ModFumos.RAINBOW_PIGMEE_FUMO_ITEM.get())) {
                remaining.set(slot, stack.copyWithCount(1));
            }
        }
        return remaining;
    }

    public static final class Serializer implements RecipeSerializer<RainbowPigmeeDyeRecipe> {
        private static final ShapedRecipe.Serializer DELEGATE = new ShapedRecipe.Serializer();

        @Override
        public RainbowPigmeeDyeRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new RainbowPigmeeDyeRecipe(DELEGATE.fromJson(id, json));
        }

        @Override
        public RainbowPigmeeDyeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new RainbowPigmeeDyeRecipe(DELEGATE.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, RainbowPigmeeDyeRecipe recipe) {
            DELEGATE.toNetwork(buffer, recipe);
        }
    }
}
