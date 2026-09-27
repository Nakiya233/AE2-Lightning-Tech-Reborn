package com.moakiee.ae2lt.debug;

import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Native 1.20 crafting container for the backported recipe fixtures. */
final class ForgeCraftingInput extends TransientCraftingContainer {
    private ForgeCraftingInput(int width, int height) {
        super(new AbstractContainerMenu(null, -1) {
            @Override public boolean stillValid(Player player) { return true; }
            @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        }, width, height);
    }
    static ForgeCraftingInput of(int width, int height, List<ItemStack> items) {
        var input = new ForgeCraftingInput(width, height);
        for (int i = 0; i < items.size(); i++) input.setItem(i, items.get(i));
        return input;
    }
    List<ItemStack> items() {
        return IntStream.range(0, getContainerSize()).mapToObj(this::getItem).toList();
    }
}
