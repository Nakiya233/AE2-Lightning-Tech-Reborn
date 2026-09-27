package com.moakiee.ae2lt.mixin.client;

import appeng.api.stacks.*;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.core.localization.*;
import appeng.menu.me.common.MEStorageMenu;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.moakiee.ae2lt.menu.TianshuMaintenanceMenu;
import com.moakiee.thunderbolt.ae2.crafting.ExactAmountFormatter;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MEStorageScreen.class, remap = false)
public abstract class BigTerminalAmountMixin extends AEBaseScreen<MEStorageMenu> {
    protected BigTerminalAmountMixin(MEStorageMenu m, Inventory i, Component t, ScreenStyle s) {
        super(m, i, t, s);
    }

    @WrapOperation(
            // This overrides a vanilla method and is SRG-named in the Forge release JAR.
            method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V",
            remap = true,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lappeng/api/stacks/AEKey;formatAmount(JLappeng/api/stacks/AmountFormat;)Ljava/lang/String;",
                            remap = false))
    private String ae2lt$amount(
            AEKey key, long amount, AmountFormat format, Operation<String> original) {
        if (menu instanceof TianshuMaintenanceMenu m && m.getBigStock(key) != null)
            return ExactAmountFormatter.slot(m.getBigStock(key), key.getAmountPerUnit());
        return original.call(key, amount, format);
    }

    @WrapOperation(
            method = "renderGridInventoryEntryTooltip",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lappeng/core/localization/Tooltips;getAmountTooltip(Lappeng/core/localization/ButtonToolTips;Lappeng/api/stacks/AEKey;J)Lnet/minecraft/network/chat/Component;"))
    private Component ae2lt$tooltip(
            ButtonToolTips label, AEKey key, long amount, Operation<Component> original) {
        if (menu instanceof TianshuMaintenanceMenu m && m.getBigStock(key) != null)
            return label.text(
                    ExactAmountFormatter.full(m.getBigStock(key), key.getAmountPerUnit()));
        return original.call(label, key, amount);
    }
}
