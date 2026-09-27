package com.moakiee.ae2lt.client;

import java.util.List;
import java.util.Locale;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.ActionButton;
import appeng.api.config.ActionItems;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.Blitter;
import com.moakiee.ae2lt.client.gui.LightningStatusIconWidget;
import com.moakiee.ae2lt.client.gui.LightningStatusLines;
import com.moakiee.ae2lt.blockentity.MiningFactoryBlockEntity;
import com.moakiee.ae2lt.blockentity.MiningFactoryBlockEntity.Status;
import com.moakiee.ae2lt.menu.MiningFactoryMenu;
import com.moakiee.ae2lt.client.gui.LargeStackCountRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class MiningFactoryScreen extends AEBaseScreen<MiningFactoryMenu> {
    private final Blitter miningOverlay;
    private final ToggleButton autoExportButton;
    private final ActionButton configureOutputButton;
    public MiningFactoryScreen(MiningFactoryMenu menu, Inventory inventory, Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        imageWidth = 176;
        imageHeight = 183;
        miningOverlay = style.getImage("miningOverlay");
        widgets.add("processArea", new OverloadProcessingFactoryProgressWidget(menu::getProgress, style.getImage("processOverlay")));
        widgets.add("energyBar", new OverloadProcessingFactoryEnergyBar(() -> menu.energy, () -> 1_000_000L, style.getImage("energyBar")));
        widgets.add("lightningStatus", new LightningStatusIconWidget(() -> List.of(
                LightningStatusLines.title(),
                Component.translatable("ae2lt.gui.status.label", statusText()),
                LightningStatusLines.progress(menu.getProgress()),
                Component.translatable("gui.ae2lt.mining_factory.duration", MiningFactoryBlockEntity.PROCESSING_TICKS),
                Component.translatable("gui.ae2lt.mining_factory.parallel", menu.parallelCapacity),
                Component.translatable("gui.ae2lt.mining_factory.lightning", menu.lightning),
                LightningStatusLines.energy(menu.energy, 1_000_000))));
        addToLeftToolbar(FrequencyBindingClient.createToolbarButton(menu));
        autoExportButton = new ToggleButton(Icon.AUTO_EXPORT_ON, Icon.AUTO_EXPORT_OFF,
                state -> menu.clientToggleAutoExport());
        autoExportButton.setTooltipOn(List.of(Component.translatable("ae2lt.gui.overload_factory.auto_export.title"),
                Component.translatable("ae2lt.gui.overload_factory.auto_export.on")));
        autoExportButton.setTooltipOff(List.of(Component.translatable("ae2lt.gui.overload_factory.auto_export.title"),
                Component.translatable("ae2lt.gui.overload_factory.auto_export.off")));
        addToLeftToolbar(autoExportButton);
        configureOutputButton = new ActionButton(ActionItems.TERMINAL_SETTINGS, () -> switchToScreen(createOutputConfigScreen()));
        configureOutputButton.setMessage(Component.translatable("ae2lt.gui.overload_factory.configure_output"));
        addToLeftToolbar(configureOutputButton);
    }

    public MachineOutputConfigScreen<MiningFactoryMenu, MiningFactoryScreen> createOutputConfigScreen() {
        return new MachineOutputConfigScreen<>(this, Component.translatable("block.ae2lt.mining_factory"));
    }

    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        autoExportButton.setState(menu.isAutoExportEnabled());
        configureOutputButton.setVisibility(menu.isAutoExportEnabled());
    }

    @Override public void drawBG(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        super.drawBG(graphics, x, y, mouseX, mouseY, partialTicks);
        // Reveal the falling fragments from top to bottom using the same batch progress as the arrow.
        int filled = net.minecraft.util.Mth.clamp((int) Math.ceil(miningOverlay.getSrcHeight() * menu.getProgress()),
                0, miningOverlay.getSrcHeight());
        if (filled > 0) {
            miningOverlay.copy()
                    .src(miningOverlay.getSrcX(), miningOverlay.getSrcY(), miningOverlay.getSrcWidth(), filled)
                    .dest(x + 25, y + 43, miningOverlay.getSrcWidth(), filled)
                    .blit(graphics);
        }
    }

    private Component statusText() {
        String status = Status.values()[net.minecraft.util.Mth.clamp(menu.status, 0, Status.values().length - 1)].name().toLowerCase(Locale.ROOT);
        return Component.translatable("gui.ae2lt.mining_factory.status." + status);
    }

    @Override public void renderSlot(GuiGraphics graphics, Slot slot) {
        super.renderSlot(graphics, slot);
        LargeStackCountRenderer.renderSlotCount(graphics, font, slot);
    }

    @Override protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        var lines = super.getTooltipFromContainerItem(stack);
        LargeStackCountRenderer.appendCountTooltip(lines, hoveredSlot);
        return lines;
    }
}
