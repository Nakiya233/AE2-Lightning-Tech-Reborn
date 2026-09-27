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
public final class PigmeeBuildingRecipe extends ShapedRecipe {
    private PigmeeBuildingRecipe(ShapedRecipe recipe) {
        super(recipe.getId(), recipe.getGroup(), recipe.category(), recipe.getWidth(), recipe.getHeight(),
                recipe.getIngredients(), recipe.getResultItem(null), recipe.showNotification());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeTypes.PIGMEE_BUILDING_SERIALIZER.get();
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

    public static final class Serializer implements RecipeSerializer<PigmeeBuildingRecipe> {
        private static final ShapedRecipe.Serializer DELEGATE = new ShapedRecipe.Serializer();

        @Override
        public PigmeeBuildingRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new PigmeeBuildingRecipe(DELEGATE.fromJson(id, json));
        }

        @Override
        public PigmeeBuildingRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new PigmeeBuildingRecipe(DELEGATE.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, PigmeeBuildingRecipe recipe) {
            DELEGATE.toNetwork(buffer, recipe);
        }
    }
}
