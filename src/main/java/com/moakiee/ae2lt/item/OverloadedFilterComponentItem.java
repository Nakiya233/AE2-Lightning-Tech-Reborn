package com.moakiee.ae2lt.item;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.collect.MapMaker;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;

/**
 * Filter component item for the Overloaded ME Interface and IO Port.
 * <p>
 * Configurable in AE2's Cell Workbench:
 * <ul>
 *   <li>63 filter slots (all registered AE key types, including chemicals)</li>
 *   <li>2 upgrade slots (fuzzy card + inverter card)</li>
 *   <li>Supports fuzzy matching and whitelist/blacklist inversion</li>
 * </ul>
 * The interface filters incoming resources; the IO Port filters both transfer directions.
 */
public class OverloadedFilterComponentItem extends AE2LTItem implements ICellWorkbenchItem {

    private static final int CONFIG_SLOTS = 63;
    private static final int UPGRADE_SLOTS = 2;
    private static final String FUZZY_MODE_TAG = "FuzzyMode";

    public OverloadedFilterComponentItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isEditable(ItemStack stack) {
        return true;
    }

    @Override
    public ConfigInventory getConfigInventory(ItemStack stack) {
        return CellConfig.create(
                key -> true,
                stack, CONFIG_SLOTS);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack stack) {
        String fuzzyMode = stack.getOrCreateTag().getString(FUZZY_MODE_TAG);
        try {
            return FuzzyMode.valueOf(fuzzyMode);
        } catch (IllegalArgumentException ignored) {
            return FuzzyMode.IGNORE_ALL;
        }
    }

    @Override
    public void setFuzzyMode(ItemStack stack, FuzzyMode mode) {
        stack.getOrCreateTag().putString(FUZZY_MODE_TAG, mode.name());
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        return UpgradeInventories.forItem(stack, UPGRADE_SLOTS);
    }

    /** Snapshot of the configured rules, matching the interface's empty/fuzzy/inverted semantics. */
    public Predicate<AEKey> createMatcher(ItemStack stack) {
        var config = getConfigInventory(stack);
        var configured = new HashSet<AEKey>();
        for (int i = 0; i < config.size(); i++) {
            var key = config.getKey(i);
            if (key != null) configured.add(key);
        }
        var upgrades = getUpgrades(stack);
        var fuzzy = upgrades.isInstalled(AEItems.FUZZY_CARD) ? getFuzzyMode(stack) : null;
        boolean inverted = upgrades.isInstalled(AEItems.INVERTER_CARD);
        return createMatcher(configured, fuzzy, inverted);
    }

    /**
     * A rule snapshot with a transient AEKey -> boolean cache. Guava weak keys use
     * identity equality: a hit never hashes or compares the key's components.
     * Both outcomes are cached; Boolean values cannot retain their keys. Rebuild
     * the matcher when rules change. No cache entries are written to the item.
     */
    public static Predicate<AEKey> createMatcher(Set<AEKey> configured,
                                                 @Nullable FuzzyMode fuzzy, boolean inverted) {
        if (configured.isEmpty()) return key -> true;
        var keys = Set.copyOf(configured);
        Map<AEKey, Boolean> results = new MapMaker().weakKeys().concurrencyLevel(1).makeMap();
        return key -> {
            var cached = results.get(key);
            if (cached != null) return cached;
            boolean matches = keys.contains(key);
            if (!matches && fuzzy != null) {
                for (var configuredKey : keys) {
                    if (key.fuzzyEquals(configuredKey, fuzzy)) {
                        matches = true;
                        break;
                    }
                }
            }
            boolean allowed = matches != inverted;
            results.put(key, allowed);
            return allowed;
        };
    }
}

