package com.moakiee.ae2lt.mixin;

import java.util.Map;

import com.google.gson.JsonElement;
import com.moakiee.ae2lt.machine.overloadfactory.recipe.InscriberProcessorRecipeLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Derive at the vanilla reload boundary, before optional recipe-script processing.
// Run after the default 1000 hooks but ahead of that late recipe-script hook.
// The KJS integration GameTest checks this order on initial load and /reload.
@Mixin(value = RecipeManager.class, priority = 1088)
public abstract class InscriberProcessorRecipeLoaderMixin {
    @Shadow(remap = false) @Final private ICondition.IContext context;
    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD"))
    private void ae2lt$deriveBeforeRecipeScripts(Map<ResourceLocation, JsonElement> recipes,
            ResourceManager resources, ProfilerFiller profiler, CallbackInfo ci) {
        InscriberProcessorRecipeLoader.addRecipes(recipes, context);
    }
}
