package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;

/** Adds fresh JSON before recipe scripts so script removal and replacement remain authoritative. */
public final class InscriberProcessorRecipeLoader {
    private static final Set<String> SOURCE_TYPES = Set.of("ae2:inscriber", "minecraft:crafting_shaped",
            "minecraft:crafting_shapeless", "ae2lt:overload_processing");
    private InscriberProcessorRecipeLoader() { }

    public static void addRecipes(Map<ResourceLocation, JsonElement> json, ICondition.IContext context) {
        var sources = new ArrayList<RecipeHolder<?>>();
        var existing = new ArrayList<RecipeHolder<OverloadProcessingRecipe>>();
        for (var entry : json.entrySet()) {
            if (entry.getKey().getPath().startsWith("_") || !entry.getValue().isJsonObject()) continue;
            var data = entry.getValue().getAsJsonObject();
            var type = data.get("type");
            if (type == null || !type.isJsonPrimitive() || !SOURCE_TYPES.contains(type.getAsString())) continue;
            try {
                if (!CraftingHelper.processConditions(data, "conditions", context)) continue;
                var recipe = RecipeManager.fromJson(entry.getKey(), data, context);
                if (recipe == null) continue;
                sources.add(new RecipeHolder<>(entry.getKey(), recipe));
                if (recipe instanceof OverloadProcessingRecipe nativeRecipe) {
                    existing.add(new RecipeHolder<>(entry.getKey(), nativeRecipe));
                }
            } catch (IllegalArgumentException | com.google.gson.JsonParseException invalid) {
                // The normal recipe loader reports malformed original recipes.
            }
        }
        int count = 0;
        for (var holder : InscriberProcessorAdapter.derive(sources, existing,
                new ReloadIngredientLookup(context, JsonOps.INSTANCE))) {
            if (json.containsKey(holder.id())) continue;
            json.put(holder.id(), encode(holder.value()));
            count++;
        }
        LogUtils.getLogger().info("Generated {} missing bulk inscriber recipes before recipe scripts", count);
    }

    static JsonObject encode(OverloadProcessingRecipe recipe) {
        var json = new JsonObject();
        json.addProperty("type", "ae2lt:overload_processing");
        json.addProperty("priority", recipe.priority());
        var inputs = new JsonArray();
        for (var input : recipe.itemInputs()) {
            var value = new JsonObject();
            value.add("ingredient", input.ingredient().toJson());
            value.addProperty("count", input.count());
            inputs.add(value);
        }
        json.add("inputs", inputs);
        var results = new JsonArray();
        for (var output : recipe.itemResults()) {
            var value = new JsonObject();
            value.addProperty("id", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
            value.addProperty("count", output.getCount());
            if (output.hasTag()) value.addProperty("nbt", output.getTag().toString());
            results.add(value);
        }
        json.add("results", results);
        json.addProperty("totalEnergy", recipe.totalEnergy());
        json.addProperty("lightningCost", recipe.lightningCost());
        json.addProperty("lightningTier", recipe.lightningTier().getSerializedName());
        return json;
    }
}
