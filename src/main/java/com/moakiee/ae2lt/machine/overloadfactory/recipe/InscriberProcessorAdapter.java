package com.moakiee.ae2lt.machine.overloadfactory.recipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import appeng.recipes.handlers.InscriberProcessType;
import appeng.recipes.handlers.InscriberRecipe;
import com.mojang.logging.LogUtils;
import com.moakiee.ae2lt.me.key.LightningKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import com.moakiee.ae2lt.machine.overloadfactory.recipe.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;

/** Missing bulk processor recipes, derived before recipe scripts from the datapack recipe graph. */
final class InscriberProcessorAdapter {
    private static final int BASE_OPERATIONS = 36;
    private static final long BASE_ENERGY = 400_000;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VARIANTS = 128;
    private static final int MAX_OPERATIONS = 1_000_000;

    private final List<RecipeHolder<InscriberRecipe>> printing;
    private final List<Compression> compressions;
    private int visited;
    private final UnaryOperator<Ingredient> lookup;

    private InscriberProcessorAdapter(List<RecipeHolder<InscriberRecipe>> inscribers,
            Collection<? extends RecipeHolder<?>> sources, UnaryOperator<Ingredient> lookup) {
        this.lookup = lookup;
        printing = inscribers.stream().filter(holder -> {
            var recipe = holder.value();
            // INSCRIBE preserves both side slots; only one occupied template is unfolded.
            return recipe.getProcessType() == InscriberProcessType.INSCRIBE
                    && (recipe.getTopOptional().isEmpty() != recipe.getBottomOptional().isEmpty())
                    && hasItems(recipe.getMiddleInput())
                    && !recipe.getResultItem().isEmpty()
                    && hasItems(recipe.getTopOptional().isEmpty() ? recipe.getBottomOptional() : recipe.getTopOptional());
        }).toList();
        compressions = compressions(sources);
    }

    static List<RecipeHolder<OverloadProcessingRecipe>> derive(Collection<? extends RecipeHolder<?>> sources,
            List<RecipeHolder<OverloadProcessingRecipe>> existing) {
        return derive(sources, existing, UnaryOperator.identity());
    }

    static List<RecipeHolder<OverloadProcessingRecipe>> derive(Collection<? extends RecipeHolder<?>> sources,
            List<RecipeHolder<OverloadProcessingRecipe>> existing, UnaryOperator<Ingredient> lookup) {
        var inscribers = new ArrayList<RecipeHolder<InscriberRecipe>>();
        for (var holder : sources) {
            // Subclasses may have extra matching or output rules not represented by these fields.
            if (holder.value().getClass() == InscriberRecipe.class) {
                inscribers.add(new RecipeHolder<>(holder.id(), (InscriberRecipe) holder.value()));
            }
        }
        inscribers.sort(Comparator.comparing(holder -> holder.id().toString()));
        var adapter = new InscriberProcessorAdapter(inscribers, sources, lookup);
        var result = new ArrayList<RecipeHolder<OverloadProcessingRecipe>>();
        for (var holder : inscribers) {
            // Only final processor recipes opt in; intermediate print IDs need no naming convention.
            if (!holder.id().getPath().contains("processor")) continue;
            var recipe = holder.value();
            var output = recipe.getResultItem();
            if (recipe.getProcessType() != InscriberProcessType.PRESS || output.isEmpty()
                    || recipe.getIngredients().stream().anyMatch(i -> i.isEmpty() || !adapter.hasItems(i))) continue;
            // Any explicit factory route for this exact output wins, regardless of its ID.
            if (existing.stream().flatMap(h -> h.value().itemResults().stream())
                    .anyMatch(stack -> ItemStack.isSameItemSameTags(stack, output))) continue;
            try {
                adapter.visited = 0;
                var choices = recipe.getIngredients().stream()
                        .map(ingredient -> adapter.resolve(ingredient, 1, new HashSet<>(), 0)).toList();
                var generated = new ArrayList<RecipeHolder<OverloadProcessingRecipe>>();
                adapter.combine(holder, choices, 0, new ArrayList<>(), generated);
                result.addAll(generated);
            } catch (IllegalArgumentException | ArithmeticException e) {
                LogUtils.getLogger().warn("Overload factory cannot derive inscriber {}: {}", holder.id(), e.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private List<Material> resolve(Ingredient ingredient, int units, Set<ResourceLocation> path, int depth) {
        if (++visited > 10_000) throw new IllegalArgumentException("inscriber graph is too large");
        if (depth >= MAX_DEPTH) return List.of(new Material(ingredient, units, "raw"));
        var candidates = printing.stream().filter(h -> matches(ingredient, h.value().getResultItem())).toList();
        var resolved = new ArrayList<Material>();
        var covered = new ArrayList<ItemStack>();
        for (var holder : candidates) {
            if (!path.add(holder.id())) continue;
            try {
                int amount = checkedMultiply(units, holder.value().getResultItem().getCount());
                // Stop a cyclic route at its existing ingredient, without dropping any consumed material.
                for (var material : resolve(holder.value().getMiddleInput(), amount, path, depth + 1)) {
                    resolved.add(new Material(material.ingredient(), material.units(), holder.id() + "/" + material.route()));
                }
                covered.add(holder.value().getResultItem());
            } catch (ArithmeticException e) {
                // The original intermediate remains usable if this expansion needs an excessive batch.
            } finally {
                path.remove(holder.id());
            }
        }
        if (!fullyCovered(ingredient, covered)) {
            var blocks = compressions.stream().filter(c -> matches(ingredient, c.material())).toList();
            var compressed = new ArrayList<ItemStack>();
            for (var block : blocks) {
                try {
                    resolved.add(new Material(StrictNBTIngredient.of( block.block()),
                            checkedMultiply(units, block.units()), "block/" + block.route()));
                    compressed.add(block.material());
                } catch (ArithmeticException ignored) { }
            }
            // Keep the original predicate (including tags/custom components) if any alternatives remain.
            var allCovered = new ArrayList<>(covered);
            allCovered.addAll(compressed);
            if (!fullyCovered(ingredient, allCovered)) resolved.add(new Material(ingredient, units, "raw"));
        }
        return merge(resolved);
    }

    private boolean fullyCovered(Ingredient ingredient, List<ItemStack> outputs) {
        var resolved = lookup.apply(ingredient);
        return ingredient.getClass() == Ingredient.class && resolved != null && resolved.getItems().length > 0
                && Arrays.stream(resolved.getItems()).allMatch(stack -> outputs.stream().anyMatch(o -> o.is(stack.getItem())));
    }

    private static List<Material> merge(List<Material> materials) {
        Map<Integer, List<Material>> groups = new TreeMap<>();
        for (var material : materials) groups.computeIfAbsent(material.units(), key -> new ArrayList<>()).add(material);
        return groups.entrySet().stream().map(entry -> {
            var group = entry.getValue();
            var ingredient = group.size() == 1 ? group.get(0).ingredient()
                    : CompoundIngredient.of(group.stream().map(Material::ingredient).toArray(Ingredient[]::new));
            var route = group.stream().map(Material::route).sorted().reduce((a, b) -> a + ";" + b).orElseThrow();
            return new Material(ingredient, entry.getKey(), route);
        }).toList();
    }

    private void combine(RecipeHolder<InscriberRecipe> source, List<List<Material>> choices, int slot,
            List<Material> selected, List<RecipeHolder<OverloadProcessingRecipe>> result) {
        if (result.size() >= MAX_VARIANTS) throw new IllegalArgumentException("too many derived variants");
        if (slot < choices.size()) {
            for (var material : choices.get(slot)) {
                selected.add(material);
                combine(source, choices, slot + 1, selected, result);
                selected.remove(selected.size() - 1);
            }
            return;
        }
        int operations = BASE_OPERATIONS;
        for (var material : selected) operations = checkedMultiply(operations / gcd(operations, material.units()), material.units());
        int batch = operations;
        var inputs = selected.stream().map(m -> new OverloadProcessingIngredient(m.ingredient(), batch / m.units())).toList();
        var output = source.value().getResultItem().copyWithCount(Math.multiplyExact(batch, source.value().getResultItem().getCount()));
        String route = selected.stream().map(m -> m.units() + ":" + m.route()).reduce((a, b) -> a + "|" + b).orElseThrow();
        var id = new ResourceLocation("ae2lt", "derived/inscriber/" + source.id().getNamespace()
                + "/" + source.id().getPath() + "/" + digest(route));
        var recipe = new OverloadProcessingRecipe(id, -1, inputs, FluidStack.EMPTY, List.of(output), FluidStack.EMPTY,
                BASE_ENERGY * (batch / BASE_OPERATIONS), batch / BASE_OPERATIONS, LightningKey.Tier.HIGH_VOLTAGE);
        result.add(new RecipeHolder<>(id, recipe));
    }

    private List<Compression> compressions(Collection<? extends RecipeHolder<?>> sources) {
        var crafting = sources.stream().filter(h -> standardCrafting(h.value()))
                .sorted(Comparator.comparing(h -> h.id().toString())).toList();
        var unpacking = crafting.stream().filter(h -> h.value().getIngredients().size() == 1).toList();
        var result = new ArrayList<Compression>();
        for (var holder : crafting) {
            var recipe = holder.value();
            var ingredients = recipe.getIngredients();
            if (ingredients.size() != 4 && ingredients.size() != 9) continue;
            if (recipe instanceof ShapedRecipe shaped && shaped.getWidth() != shaped.getHeight()) continue;
            var first = ingredients.get(0);
            if (first.isEmpty() || first.getClass() != Ingredient.class || !hasItems(first)
                    || ingredients.stream().anyMatch(i -> i.isEmpty() || i.getClass() != Ingredient.class || !hasItems(i))) continue;
            var block = recipe.getResultItem(RegistryAccess.EMPTY);
            if (block.getCount() != 1 || !(block.getItem() instanceof BlockItem) || block.hasCraftingRemainingItem()) continue;
            for (var reverseHolder : unpacking) {
                var reverse = reverseHolder.value();
                var reverseInputs = reverse.getIngredients();
                if (reverseInputs.size() != 1 || !matches(reverseInputs.get(0), block)) continue;
                var material = reverse.getResultItem(RegistryAccess.EMPTY);
                if (material.getCount() != ingredients.size() || ingredients.stream().anyMatch(i -> !matches(i, material)) || material.hasCraftingRemainingItem()) continue;
                result.add(new Compression(block.copy(), material.copyWithCount(1), ingredients.size(), holder.id() + "/" + reverseHolder.id()));
            }
        }
        return List.copyOf(result);
    }

    private boolean hasItems(Ingredient ingredient) {
        var resolved = lookup.apply(ingredient);
        // An unresolved custom predicate is retained as an input, never flattened.
        return resolved == null || resolved.getItems().length > 0;
    }

    private boolean matches(Ingredient ingredient, ItemStack stack) {
        var resolved = lookup.apply(ingredient);
        return resolved != null && resolved.test(stack);
    }

    private static boolean standardCrafting(Recipe<?> recipe) {
        return recipe.getClass() == ShapedRecipe.class || recipe.getClass() == ShapelessRecipe.class;
    }

    private static int checkedMultiply(int a, int b) {
        int value = Math.multiplyExact(a, b);
        if (value <= 0 || value > MAX_OPERATIONS) throw new ArithmeticException("derived batch is too large");
        return value;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int next = a % b; a = b; b = next; }
        return a;
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Material(Ingredient ingredient, int units, String route) { }
    private record Compression(ItemStack block, ItemStack material, int units, String route) { }
}
