package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import java.util.Map;
import java.util.stream.Collectors;
import com.moakiee.ae2lt.util.RecipeManagerByTypeAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.crafting.conditions.ICondition;

/** Mirrors the identity exposed by the production Mixin; unit JVMs do not apply Mixins. */
final class TestRecipeManager extends RecipeManager implements RecipeManagerByTypeAccess {
    private Object snapshot = new Object();
    TestRecipeManager() { super(ICondition.IContext.EMPTY); }
    @Override public void replaceRecipes(Iterable<Recipe<?>> recipes) {
        super.replaceRecipes(recipes);
        snapshot = new Object();
    }
    @Override public Object ae2lt$recipeSnapshot() { return snapshot; }
    @Override public <C extends Container, T extends Recipe<C>> Map<ResourceLocation, T> ae2lt$getByType(RecipeType<T> type) {
        return getAllRecipesFor(type).stream().collect(Collectors.toMap(Recipe::getId, recipe -> recipe));
    }
}
