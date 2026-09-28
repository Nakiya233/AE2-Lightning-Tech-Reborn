package com.moakiee.ae2lt.item;

import java.util.List;

import com.moakiee.ae2lt.device.capability.DeviceCapability;
import com.moakiee.ae2lt.celestweave.ArmorOverloadRules;
import com.moakiee.ae2lt.celestweave.ArmorPart;
import com.moakiee.ae2lt.celestweave.module.OverloadProtectionSubmodule;

public final class OverloadProtectionSubmoduleItem extends AbstractSingleArmorSubmoduleItem {

    public OverloadProtectionSubmoduleItem(Properties properties) {
        super(
                properties,
                ArmorPart.CHEST,
                OverloadProtectionSubmodule.INSTANCE,
                stack -> List.of(
                        new DeviceCapability.StagedMitigation(OverloadProtectionSubmodule.ID),
                        new DeviceCapability.LastStandTuning(
                                ArmorOverloadRules.UNDYING_TRIGGER_COST_FE, 0),
                        new DeviceCapability.PassiveDrain(ArmorOverloadRules.UNDYING_PASSIVE_DRAIN_FE)));
    }
}
