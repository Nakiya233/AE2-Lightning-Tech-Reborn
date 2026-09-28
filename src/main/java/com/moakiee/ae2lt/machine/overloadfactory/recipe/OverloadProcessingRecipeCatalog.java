package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.WeakHashMap;

import com.mojang.logging.LogUtils;
import com.moakiee.ae2lt.registry.ModRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import com.moakiee.ae2lt.machine.overloadfactory.recipe.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TagsUpdatedEvent;

/**
 * The factory's execution catalog. Borrowed reaction views never enter RecipeManager.
 * Reaction views use the original viewer category; expanded inscriber views use the factory category.
 * All sources, including pre-script inscriber wrappers, come from the final post-script recipe manager.
 */
@EventBusSubscriber(modid = "ae2lt")
public final class OverloadProcessingRecipeCatalog {
    private static final Map<RecipeManager, Snapshot> CACHE = new WeakHashMap<>();
    private static final Comparator<RecipeHolder<OverloadProcessingRecipe>> ORDER = Comparator
            .<RecipeHolder<OverloadProcessingRecipe>>comparingInt(holder -> holder.value().priority()).reversed()
            .thenComparing(Comparator.comparingInt(
                    (RecipeHolder<OverloadProcessingRecipe> holder) -> holder.value().itemInputs().size()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (RecipeHolder<OverloadProcessingRecipe> holder) -> holder.value().totalInputCount()).reversed())
            .thenComparing(holder -> holder.id().toString());

    private OverloadProcessingRecipeCatalog() {
    }

    public static synchronized List<OverloadProcessingRecipe> recipes(RecipeManager manager) {
        var previous = CACHE.get(manager);
        // RecipeManager replaces its immutable byName map on both datapack reload and client sync.
        // Its values view is stable between changes, so active jobs don't walk the recipe graph per tick.
        if (previous != null && previous.sources() == snapshot(manager)) return previous.values();
        var nativeRecipes = manager.getAllRecipesFor(ModRecipeTypes.OVERLOAD_PROCESSING_TYPE.get()).stream().map(RecipeHolder::of).toList();
        List<? extends RecipeHolder<?>> reactions = ModList.get() != null && ModList.get().isLoaded("advanced_ae")
                ? AdvancedAeReactionAdapter.sourceRecipes(manager) : List.of();
        recipes(manager, nativeRecipes, reactions);
        return CACHE.get(manager).values();
    }

    public static synchronized List<OverloadProcessingRecipe> displayRecipes(RecipeManager manager) {
        recipes(manager);
        return CACHE.get(manager).displayValues();
    }

    @SubscribeEvent
    public static synchronized void tagsUpdated(TagsUpdatedEvent event) {
        CACHE.clear();
    }

    /** Both original and derived IDs use the current catalog; unchanged jobs never rescan the recipe graph. */
    public static synchronized Optional<OverloadProcessingRecipe> find(
            RecipeManager manager, ResourceLocation id) {
        var source = manager.byKey(id).orElse(null);
        if (source != null && source instanceof OverloadProcessingRecipe nativeRecipe
                && nativeRecipe.getType() == ModRecipeTypes.OVERLOAD_PROCESSING_TYPE.get()) {
            return Optional.of(nativeRecipe);
        }
        recipes(manager);
        return Optional.ofNullable(CACHE.get(manager).byId().get(id)).map(RecipeHolder::value);
    }

    // Separate source collection from conversion so reloads can be tested without registering mod globals.
    static synchronized List<RecipeHolder<OverloadProcessingRecipe>> recipes(
            RecipeManager manager, List<RecipeHolder<OverloadProcessingRecipe>> nativeRecipes,
            List<? extends RecipeHolder<?>> reactions) {
        Snapshot previous = CACHE.get(manager);
        if (previous != null && previous.sources() == snapshot(manager)
                && sameHolders(previous.nativeRecipes(), nativeRecipes)
                && sameHolders(previous.reactions(), reactions)) {
            return previous.recipes();
        }
        var recipes = new ArrayList<>(nativeRecipes);
        for (var holder : reactions) {
            try {
                recipes.add(AdvancedAeReactionAdapter.convert(holder));
            } catch (IllegalArgumentException e) {
                LogUtils.getLogger().warn("Overload factory cannot borrow reaction {}: {}", holder.id(), e.getMessage());
            }
        }
        recipes.sort(ORDER);
        var result = List.copyOf(recipes);
        var displayed = new ArrayList<>(nativeRecipes);
        displayed.sort(ORDER);
        var byId = new HashMap<ResourceLocation, RecipeHolder<OverloadProcessingRecipe>>();
        for (var recipe : result) byId.put(recipe.id(), recipe);
        CACHE.put(manager, new Snapshot(snapshot(manager), List.copyOf(nativeRecipes), List.copyOf(reactions),
                result, List.copyOf(displayed), Map.copyOf(byId),
                result.stream().map(RecipeHolder::value).toList(), displayed.stream().map(RecipeHolder::value).toList()));
        return result;
    }

    private static boolean sameHolders(List<? extends RecipeHolder<?>> left, List<? extends RecipeHolder<?>> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).id().equals(right.get(i).id()) || left.get(i).value() != right.get(i).value()) {
                return false;
            }
        }
        return true;
    }

    static Object snapshot(RecipeManager manager) {
        return ((com.moakiee.ae2lt.util.RecipeManagerByTypeAccess) manager).ae2lt$recipeSnapshot();
    }

    private record Snapshot(Object sources, List<RecipeHolder<OverloadProcessingRecipe>> nativeRecipes,
            List<? extends RecipeHolder<?>> reactions, List<RecipeHolder<OverloadProcessingRecipe>> recipes,
            List<RecipeHolder<OverloadProcessingRecipe>> displayRecipes,
            Map<ResourceLocation, RecipeHolder<OverloadProcessingRecipe>> byId,
            List<OverloadProcessingRecipe> values, List<OverloadProcessingRecipe> displayValues) {
    }
}
