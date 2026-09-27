package com.moakiee.ae2lt.compat.mining;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/** Charges harvest anointments on a prospective tool copy, without invoking real mining events. */
public final class BloodMagicMiningTool {
    private final Map<Object, Object> anointments;
    private BloodMagicMiningTool(Map<Object, Object> anointments) { this.anointments = anointments; }

    public static BloodMagicMiningTool read(ItemStack tool) {
        if (!ModList.get().isLoaded("bloodmagic")) return null;
        var data = tool.getTag();
        if (data == null || !data.contains("anointment_holder")) return null;
        try {
            Object holder = Access.READ.invoke(null, tool);
            @SuppressWarnings("unchecked")
            Map<Object, Object> entries = (Map<Object, Object>) Access.ENTRIES.invoke(holder);
            return new BloodMagicMiningTool(new HashMap<>(entries));
        } catch (ReflectiveOperationException | LinkageError e) { throw failure(e); }
    }

    /** End this batch when an effect expires; remaining input starts with the updated tool next batch. */
    public int limit(int requested) {
        try {
            for (var entry : anointments.entrySet()) {
                if ((boolean) Access.HARVEST.invoke(entry.getKey())) {
                    int left = (int) Access.MAX_DAMAGE.invoke(entry.getValue()) - (int) Access.DAMAGE.invoke(entry.getValue());
                    requested = Math.min(requested, Math.max(0, left));
                }
            }
            return requested;
        } catch (ReflectiveOperationException | LinkageError e) { throw failure(e); }
    }

    public void consume(ItemStack tool, int processed) {
        if (tool.isEmpty() || processed == 0) return;
        try {
            var remaining = new HashMap<>(anointments);
            for (var entry : anointments.entrySet()) {
                if (!(boolean) Access.HARVEST.invoke(entry.getKey())) continue;
                Access.HURT.invoke(entry.getValue(), processed);
                if ((boolean) Access.EXPIRED.invoke(entry.getValue())) remaining.remove(entry.getKey());
            }
            Object holder = Access.CONSTRUCTOR.newInstance(remaining);
            for (Object removed : anointments.keySet()) {
                if (!remaining.containsKey(removed)) {
                    // Apply only the item cleanup hook, not the holder's world sound/particle wrapper.
                    Access.REMOVE.invoke(removed, holder, tool, EquipmentSlot.MAINHAND);
                }
            }
            Access.WRITE.invoke(holder, tool);
        } catch (ReflectiveOperationException | LinkageError e) { throw failure(e); }
    }

    private static IllegalStateException failure(Throwable e) {
        return new IllegalStateException("Blood Magic mining anointment integration failed", e);
    }
    private static final class Access {
        private static final Method READ, ENTRIES, HARVEST, MAX_DAMAGE, DAMAGE, HURT, EXPIRED, REMOVE, WRITE;
        private static final Constructor<?> CONSTRUCTOR;
        static {
            try {
                var holder = Class.forName("wayoftime.bloodmagic.anointment.AnointmentHolder");
                var anointment = Class.forName("wayoftime.bloodmagic.anointment.Anointment");
                var data = Class.forName("wayoftime.bloodmagic.anointment.AnointmentData");
                CONSTRUCTOR = holder.getConstructor(Map.class);
                READ = holder.getMethod("fromItemStack", ItemStack.class);
                ENTRIES = holder.getMethod("getAnointments");
                HARVEST = anointment.getMethod("consumeOnHarvest");
                MAX_DAMAGE = data.getMethod("getMaxDamage");
                DAMAGE = data.getMethod("getDamage");
                HURT = data.getMethod("damage", int.class);
                EXPIRED = data.getMethod("isMaxDamage");
                REMOVE = anointment.getMethod("removeAnointment", holder, ItemStack.class, EquipmentSlot.class);
                WRITE = holder.getMethod("toItemStack", ItemStack.class);
            } catch (ReflectiveOperationException | LinkageError e) { throw failure(e); }
        }
    }
}
