package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.conditions.ICondition;

/** Matches against this reload's pending tags, without binding or changing the global registries. */
final class ReloadIngredientLookup implements UnaryOperator<Ingredient> {
    private final ICondition.IContext context;
    private final DynamicOps<JsonElement> ops;
    private final Map<Ingredient, Ingredient> cache = new IdentityHashMap<>();

    ReloadIngredientLookup(ICondition.IContext context, DynamicOps<JsonElement> ops) {
        this.context = context;
        this.ops = ops;
    }

    @Override
    public Ingredient apply(Ingredient ingredient) {
        if (cache.containsKey(ingredient)) return cache.get(ingredient);
        var json = ingredient.toJson();
        Ingredient resolved;
        if (json == null) {
            resolved = null;
        } else if (ingredient.getClass() != Ingredient.class) {
            // Unknown custom predicates may capture live tag holders. Keep them in the generated
            // recipe, but don't unfold them using stale tags from the preceding reload.
            resolved = hasTagReference(json) ? null : ingredient;
        } else {
            var stacks = new ArrayList<ItemStack>();
            collect(json, stacks);
            resolved = Ingredient.of(stacks.stream());
        }
        cache.put(ingredient, resolved);
        return resolved;
    }

    private void collect(JsonElement json, ArrayList<ItemStack> stacks) {
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(entry -> collect(entry, stacks));
        } else if (json.isJsonObject() && json.getAsJsonObject().has("tag")) {
            var tag = TagKey.create(Registries.ITEM, new ResourceLocation(json.getAsJsonObject().get("tag").getAsString()));
            context.getTag(tag).forEach(holder -> stacks.add(new ItemStack(holder)));
        } else if (json.isJsonObject() && json.getAsJsonObject().has("item")) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    new ResourceLocation(json.getAsJsonObject().get("item").getAsString()));
            stacks.add(new ItemStack(item));
        } else {
            stacks.addAll(java.util.Arrays.asList(Ingredient.fromJson(json).getItems()));
        }
    }

    private static boolean hasTagReference(JsonElement json) {
        if (json.isJsonArray()) {
            for (var entry : json.getAsJsonArray()) if (hasTagReference(entry)) return true;
        } else if (json.isJsonObject()) {
            for (var entry : json.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("tag") || hasTagReference(entry.getValue())) return true;
            }
        } else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            return json.getAsString().startsWith("#");
        }
        return false;
    }
}
