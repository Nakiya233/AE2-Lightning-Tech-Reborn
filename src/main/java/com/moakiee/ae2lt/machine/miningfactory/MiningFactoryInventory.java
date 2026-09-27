package com.moakiee.ae2lt.machine.miningfactory;

import com.moakiee.ae2lt.machine.lightningchamber.LargeStackItemHandler;
import net.minecraft.world.item.ItemStack;
import com.moakiee.ae2lt.machine.overloadfactory.OverloadProcessingFactoryInventory;
import com.moakiee.ae2lt.registry.ModItems;

public final class MiningFactoryInventory extends LargeStackItemHandler {
    public static final int INPUT = 0;
    public static final int TOOL = 1;
    public static final int OUTPUT = 2;
    public static final int OUTPUT_COUNT = 9;
    // Append the matrix slot so existing input, tool and output save indices stay valid.
    public static final int MATRIX = 11;
    public static final int SIZE = 12;
    public static final int CAPACITY = 4096;

    public MiningFactoryInventory(Runnable listener) { super(SIZE, listener); }

    @Override
    public int getSlotLimit(int slot) {
        validateSlotIndex(slot);
        return slot == TOOL ? 1 : slot == MATRIX ? OverloadProcessingFactoryInventory.MATRIX_SLOT_LIMIT : CAPACITY;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        validateSlotIndex(slot);
        return switch (slot) {
            case INPUT -> MiningLoot.stateOf(stack) != null;
            case TOOL -> MiningLoot.isTool(stack);
            case MATRIX -> stack.is(ModItems.LIGHTNING_COLLAPSE_MATRIX.get());
            default -> false;
        };
    }

    public int getInstalledMatrixCount() {
        ItemStack stack = getStackInSlot(MATRIX);
        return isItemValid(MATRIX, stack) ? Math.min(getSlotLimit(MATRIX), stack.getCount()) : 0;
    }

    public int getInstalledParallelCapacity() {
        int count = getInstalledMatrixCount();
        return count == 0 ? 1 : OverloadProcessingFactoryInventory.getMaxParallelForMatrixCount(count);
    }

    public static boolean isOutputSlot(int slot) {
        return slot >= OUTPUT && slot < OUTPUT + OUTPUT_COUNT;
    }

    public ItemStack insertOutput(ItemStack stack) {
        // Fill matching slots before claiming an empty slot.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = OUTPUT; i < OUTPUT + OUTPUT_COUNT && !stack.isEmpty(); i++) {
                if (getStackInSlot(i).isEmpty() == (pass == 1)) {
                    stack = insertItemUnchecked(i, stack, false);
                }
            }
        }
        return stack;
    }

    public boolean hasOutputRoom() {
        for (int i = OUTPUT; i < OUTPUT + OUTPUT_COUNT; i++) {
            if (getStackInSlot(i).getCount() < CAPACITY) return true;
        }
        return false;
    }
}
