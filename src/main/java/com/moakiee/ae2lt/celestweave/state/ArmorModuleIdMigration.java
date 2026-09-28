package com.moakiee.ae2lt.celestweave.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import com.moakiee.ae2lt.device.module.OverloadDeviceModuleItem;

/** Migrates persistent module keys as well as the registry alias for their providing item. */
final class ArmorModuleIdMigration {
    private static final String OLD_ID = "undying";
    private static final String NEW_ID = "overload_protection";

    private ArmorModuleIdMigration() {
    }

    static List<ItemStack> modules(List<ItemStack> copiedSource) {
        if (copiedSource.stream().noneMatch(stack -> NEW_ID.equals(moduleId(stack)))) {
            return copiedSource;
        }
        // Former undying and phase/matrix could coexist. The upgraded module incorporates
        // those shields, so legacy installed lower tiers are consumed on load, not refunded.
        return copiedSource.stream().filter(stack -> !isLowerShield(moduleId(stack))).toList();
    }

    private static String moduleId(ItemStack stack) {
        return stack.getItem() instanceof OverloadDeviceModuleItem item ? item.moduleTypeId(stack) : "";
    }

    private static boolean isLowerShield(String id) {
        return "phase_shield".equals(id) || "matrix_shield".equals(id);
    }

    static Map<String, Boolean> toggles(Map<String, Boolean> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (!source.containsKey(OLD_ID)) {
            return Map.copyOf(source);
        }
        var migrated = new LinkedHashMap<>(source);
        Boolean previous = migrated.remove(OLD_ID);
        // An explicit new setting wins over a leftover legacy setting, including false.
        migrated.putIfAbsent(NEW_ID, previous);
        return Map.copyOf(migrated);
    }

    static Map<String, CompoundTag> data(Map<String, CompoundTag> copiedSource) {
        if (!copiedSource.containsKey(OLD_ID)) {
            return copiedSource;
        }
        var migrated = new LinkedHashMap<>(copiedSource);
        CompoundTag previous = migrated.remove(OLD_ID).copy();
        CompoundTag current = migrated.get(NEW_ID);
        if (current != null) {
            previous.merge(current);
        }
        // Preserve options, combo windows, and unknown extension data; never mutate the input.
        migrated.put(NEW_ID, previous);
        return Map.copyOf(migrated);
    }
}
