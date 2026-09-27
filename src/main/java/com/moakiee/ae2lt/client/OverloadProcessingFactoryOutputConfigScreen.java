package com.moakiee.ae2lt.client;

import com.moakiee.ae2lt.menu.OverloadProcessingFactoryMenu;
import net.minecraft.network.chat.Component;

public class OverloadProcessingFactoryOutputConfigScreen
        extends MachineOutputConfigScreen<OverloadProcessingFactoryMenu, OverloadProcessingFactoryScreen> {
    public OverloadProcessingFactoryOutputConfigScreen(OverloadProcessingFactoryScreen parent) {
        super(parent, Component.translatable("block.ae2lt.overload_processing_factory"));
    }
}
