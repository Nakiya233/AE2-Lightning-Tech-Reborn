package com.moakiee.ae2lt.celestweave.module;

import java.util.List;
import org.jetbrains.annotations.Nullable;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class OverloadProtectionSubmodule extends AbstractCelestweaveArmorSubmodule {

    public static final String ID = "overload_protection";
    public static final OverloadProtectionSubmodule INSTANCE = new OverloadProtectionSubmodule();

    private OverloadProtectionSubmodule() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String nameKey() {
        return "ae2lt.celestweave.feature.overload_protection.name";
    }

    @Override
    public String descriptionKey() {
        return "ae2lt.celestweave.feature.overload_protection.desc";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public int getMaxInstallAmount() {
        return 1;
    }

    @Override
    public String installGroupId() {
        return ResistanceSubmodule.INSTALL_GROUP;
    }

    @Override
    public List<CelestweaveArmorSubmoduleConfig> getConfigs(ItemStack armor) {
        return List.of(config(
                ResistanceSubmodule.HIT_FEEDBACK_CONFIG_KEY,
                Component.translatable("ae2lt.celestweave.config.hit_feedback"),
                ByteTag.valueOf(isHitFeedbackEnabled(armor)), booleanChoices(), null));
    }

    @Override
    public boolean setConfig(ItemStack armor, String key, @Nullable Tag value) {
        if (!ResistanceSubmodule.HIT_FEEDBACK_CONFIG_KEY.equals(key)) {
            return false;
        }
        var options = getOptions(armor);
        options.put(key, value instanceof ByteTag byteTag ? byteTag : ByteTag.valueOf(true));
        setOptions(armor, options);
        return true;
    }

    public static boolean isHitFeedbackEnabled(ItemStack armor) {
        var options = INSTANCE.getOptions(armor);
        return !options.contains(ResistanceSubmodule.HIT_FEEDBACK_CONFIG_KEY, Tag.TAG_BYTE)
                || options.getBoolean(ResistanceSubmodule.HIT_FEEDBACK_CONFIG_KEY);
    }

}
