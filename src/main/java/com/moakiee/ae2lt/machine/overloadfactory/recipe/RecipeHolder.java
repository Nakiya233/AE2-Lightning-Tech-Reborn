package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

/** Stable ID/value pair for shared recipe-graph code on Forge's pre-holder recipe API. */
public record RecipeHolder<T extends Recipe<?>>(ResourceLocation id, T value) {
    public static <T extends Recipe<?>> RecipeHolder<T> of(T recipe) {
        return new RecipeHolder<>(recipe.getId(), recipe);
    }
}
