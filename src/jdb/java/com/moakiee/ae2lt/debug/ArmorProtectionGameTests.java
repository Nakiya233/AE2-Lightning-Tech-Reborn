package com.moakiee.ae2lt.debug;

import java.util.List;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import com.moakiee.ae2lt.celestweave.ArmorEnergyBuffer;
import com.moakiee.ae2lt.celestweave.CelestweaveArmorState;
import com.moakiee.ae2lt.celestweave.CelestweaveArmorUndyingHandler;
import com.moakiee.ae2lt.celestweave.module.OverloadProtectionSubmodule;
import com.moakiee.ae2lt.celestweave.service.ArmorCapabilityCollector;
import com.moakiee.ae2lt.celestweave.service.ArmorTickService;
import com.moakiee.ae2lt.device.network.ArmorNetworkBinding;
import com.moakiee.ae2lt.me.key.LightningKey;
import com.moakiee.ae2lt.registry.ModDataComponents;
import com.moakiee.ae2lt.registry.ModItems;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Actual registry/NBT, event bus, armor buffers and bound ME storage; debug source set only. */
@GameTestHolder("ae2lt_protection")
@PrefixGameTestTemplate(false)
public final class ArmorProtectionGameTests {
    private static final BlockPos AP = new BlockPos(3, 3, 3);
    private static final IActionSource ACTION = IActionSource.empty();
    private static final long CAP = 20_000_000_000L;

    private static ItemStack armor(GameTestHelper h, Item module) {
        var armor = new ItemStack(ModItems.CELESTWEAVE_CORE.get());
        CelestweaveArmorState.setSlot(armor, h.getLevel().registryAccess(), CelestweaveArmorState.SLOT_CORE,
                new ItemStack(ModItems.ULTIMATE_OVERLOAD_CORE.get()));
        require(CelestweaveArmorState.installOneModule(armor, h.getLevel().registryAccess(),
                new ItemStack(ModItems.ENERGY_MODULE_T3.get())), "energy module rejected");
        require(CelestweaveArmorState.installOneModule(armor, h.getLevel().registryAccess(),
                new ItemStack(module)), "shield module rejected");
        ArmorEnergyBuffer.write(armor, h.getLevel().registryAccess(), CAP);
        return armor;
    }

    private static ServerPlayer equip(GameTestHelper h, ItemStack armor) {
        var player = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "ShieldTest"));
        player.setItemSlot(EquipmentSlot.CHEST, armor);
        CelestweaveArmorState.syncSubmoduleActiveState(player, armor, h.getLevel().registryAccess(), true,
                Dist.DEDICATED_SERVER);
        return player;
    }

    private static void network(GameTestHelper h, ItemStack armor, long ehv) {
        h.setBlock(AP, AEBlocks.WIRELESS_ACCESS_POINT.block().defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                net.minecraft.core.Direction.UP));
        h.setBlock(AP.below(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        h.setBlock(AP.below().north(), AEBlocks.DRIVE.block());
        var cell = new ItemStack(ModItems.BULK_LIGHTNING_STORAGE_COMPONENT.get());
        var inventory = StorageCells.getCellInventory(cell, null);
        require(inventory != null, "lightning cell missing");
        require(inventory.insert(LightningKey.EXTREME_HIGH_VOLTAGE, ehv, Actionable.MODULATE, ACTION) == ehv,
                "failed to fill EHV");
        inventory.persist();
        DriveBlockEntity drive = (DriveBlockEntity) h.getBlockEntity(AP.below().north());
        drive.getInternalInventory().setItemDirect(0, cell);
        ArmorNetworkBinding.INSTANCE.bind(armor, GlobalPos.of(h.getLevel().dimension(), h.absolutePos(AP)));
    }

    private static MEStorage storage(ServerPlayer player, ItemStack armor) {
        var result = ArmorNetworkBinding.INSTANCE.resolve(armor, player);
        require(result.success(), "network binding failed: " + result.failure());
        return result.grid().getStorageService().getInventory();
    }

    private static long ehv(ServerPlayer player, ItemStack armor) {
        return storage(player, armor).extract(LightningKey.EXTREME_HIGH_VOLTAGE, Long.MAX_VALUE,
                Actionable.SIMULATE, ACTION);
    }

    private static LivingHurtEvent hit(ServerPlayer player, float amount, boolean hard) {
        ArmorCapabilityCollector.clearCache(player);
        var source = hard ? player.damageSources().magic() : player.damageSources().generic();
        var result = com.moakiee.ae2lt.celestweave.CelestweaveArmorDamageHandler.onIncomingDamage(player, source, amount);
        var event = new LivingHurtEvent(player, source, result.amount());
        event.setCanceled(result.canceled());
        return event;
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void phaseCancelsWithoutZeroingAndOnlyAbsorbs1024(GameTestHelper h) {
        var armor = armor(h, ModItems.CELESTWEAVE_SUBMODULE_PHASE_SHIELD.get());
        var player = equip(h, armor);
        network(h, armor, 10_000);
        h.runAtTickTime(40, () -> {
            var first = hit(player, 512, false);
            require(first.isCanceled() && first.getAmount() == 512, "phase must only cancel a covered hit");
            first.setCanceled(false);
            require(first.getAmount() == 512, "uncancel must restore a real phase hit");
            require(ArmorEnergyBuffer.read(armor) == CAP - 10_240_000 && ehv(player, armor) == 8_976,
                    "phase per-damage fee incorrect");
            var atCap = hit(player, 1024, false);
            require(atCap.isCanceled() && atCap.getAmount() == 1024, "1024 boundary incorrect");
            var over = hit(player, 1025, false);
            require(!over.isCanceled() && over.getAmount() == 1, "1025 should leave one point");
            var huge = hit(player, Float.MAX_VALUE, true);
            require(!huge.isCanceled() && huge.getAmount() > 1024, "phase stopped a lethal huge hit");
            require(ArmorEnergyBuffer.read(armor) == CAP - 20_480_000 && ehv(player, armor) == 7_952,
                    "phase repeated hits exceeded shared window cost");
        });
        h.runAtTickTime(60, () -> {
            hit(player, 1, false);
            require(ArmorEnergyBuffer.read(armor) == CAP - 20_500_000 && ehv(player, armor) == 7_950,
                    "phase window did not expire at exactly 20 ticks");
            h.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void ordinaryDamageHasFixedCapAndDoesNotBuildDeathCombos(GameTestHelper h) {
        var armor = armor(h, ModItems.CELESTWEAVE_SUBMODULE_OVERLOAD_PROTECTION.get());
        var player = equip(h, armor);
        ArmorEnergyBuffer.write(armor, 2_000_000_000L);
        network(h, armor, 1024);
        h.runAtTickTime(40, () -> {
            var event = hit(player, Float.MAX_VALUE, true);
            require(event.isCanceled() && event.getAmount() == 0, "overload did not zero a huge hit");
            require(ArmorEnergyBuffer.read(armor) == 0 && ehv(player, armor) == 0,
                    "first huge hit did not use the initial tier cap");
            // Reopening/reposting the same attack and reaching Pre must not create another combo.
            event.setAmount(Float.MAX_VALUE);
            event.setCanceled(false);
            MinecraftForge.EVENT_BUS.post(event);
            event.setAmount(Float.MAX_VALUE);
            MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.living.LivingDamageEvent(
                    player, event.getSource(), event.getAmount()));
            require(ArmorEnergyBuffer.read(armor) == 0 && ehv(player, armor) == 0,
                    "same damage callbacks charged more than once");
            for (int hit = 2; hit <= 100; hit++) {
                require(hit(player, Float.MAX_VALUE, false).isCanceled(), "ordinary damage raised its fixed cap");
                require(ArmorEnergyBuffer.read(armor) == 0 && ehv(player, armor) == 0,
                        "repeated ordinary damage charged extra");
            }
            ArmorNetworkBinding.INSTANCE.unbind(armor);
        });
        h.runAtTickTime(41, () -> {
            ArmorTickService.tickEquipped(player, armor, true, h.getLevel().registryAccess(), Dist.DEDICATED_SERVER);
            require(hit(player, Float.MAX_VALUE, false).isCanceled(), "paid cap failed after depletion");
            require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "death did not reuse capped credit");
            CelestweaveArmorState.setSubmoduleEnabled(armor, OverloadProtectionSubmodule.INSTANCE, false);
            ArmorTickService.tickEquipped(player, armor, true, h.getLevel().registryAccess(), Dist.DEDICATED_SERVER);
            require(!hit(player, 1, false).isCanceled(), "paid credit ignored disabled toggle");
            CelestweaveArmorState.setSubmoduleEnabled(armor, OverloadProtectionSubmodule.INSTANCE, true);
        });
        h.runAtTickTime(42, () -> {
            ArmorTickService.tickEquipped(player, armor, true, h.getLevel().registryAccess(), Dist.DEDICATED_SERVER);
            ArmorCapabilityCollector.clearCache(player);
            require(!CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player),
                    "second death should need the tier-two difference");
            require(hit(player, Float.MAX_VALUE, false).isCanceled(),
                    "unpaid higher death tier raised ordinary damage's fee cap");
        });
        h.runAtTickTime(60, () -> {
            ArmorTickService.tickEquipped(player, armor, true, h.getLevel().registryAccess(), Dist.DEDICATED_SERVER);
            require(!hit(player, 1, false).isCanceled(), "expired window gave free protection");
            h.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void directDeathCombosPayDifferencesAndResetAtTwentyTicks(GameTestHelper h) {
        var armor = armor(h, ModItems.CELESTWEAVE_SUBMODULE_OVERLOAD_PROTECTION.get());
        var player = equip(h, armor);
        network(h, armor, 16_384);
        for (int i = 0; i < 16; i++) {
            int combo = i + 1;
            h.runAtTickTime(40 + i, () -> {
                ArmorCapabilityCollector.clearCache(player);
                require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "death combo failed " + combo);
                require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "same-tick death reentry failed");
                require(hit(player, Float.MAX_VALUE, false).isCanceled(),
                        "ordinary damage after death required another tier payment");
                require(ArmorEnergyBuffer.read(armor) == CAP - Math.min(CAP, 2_000_000_000L * combo)
                                && ehv(player, armor) == 16_384 - 1024L * combo,
                        "wrong death fee/reentry charged twice at combo " + combo);
            });
        }
        h.runAtTickTime(59, () -> {
            ArmorNetworkBinding.INSTANCE.unbind(armor);
            ArmorTickService.tickEquipped(player, armor, true, h.getLevel().registryAccess(), Dist.DEDICATED_SERVER);
            require(hit(player, Float.MAX_VALUE, false).isCanceled(), "capped shield required extra resources");
            require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "capped death required extra resources");
        });
        h.runAtTickTime(60, () -> {
            ArmorTickService.tickEquipped(player, armor, true, h.getLevel().registryAccess(), Dist.DEDICATED_SERVER);
            ArmorCapabilityCollector.clearCache(player);
            require(!CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "combo extended the fixed window");
            ArmorNetworkBinding.INSTANCE.bind(armor, GlobalPos.of(h.getLevel().dimension(), h.absolutePos(AP)));
            storage(player, armor).insert(LightningKey.EXTREME_HIGH_VOLTAGE, 1024, Actionable.MODULATE, ACTION);
            ArmorEnergyBuffer.write(armor, 2_000_000_000L);
            CelestweaveArmorState.setModulesPowered(armor, true);
            CelestweaveArmorState.syncSubmoduleActiveState(player, armor, h.getLevel().registryAccess(), true,
                    Dist.DEDICATED_SERVER);
            ArmorCapabilityCollector.clearCache(player);
            require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "new window did not reset combo");
            require(ArmorEnergyBuffer.read(armor) == 0 && ehv(player, armor) == 0, "new window charged the wrong tier");
            h.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void mixedShieldDeathComboAndFailedPaymentDoNotDoubleBill(GameTestHelper h) {
        var armor = armor(h, ModItems.CELESTWEAVE_SUBMODULE_OVERLOAD_PROTECTION.get());
        var player = equip(h, armor);
        network(h, armor, 16_384);
        h.runAtTickTime(40, () -> {
            ArmorEnergyBuffer.write(armor, 1_999_999_999L);
            require(!CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "underfunded death was protected");
            require(ArmorEnergyBuffer.read(armor) == 1_999_999_999L && ehv(player, armor) == 16_384,
                    "failed death took partial resources");
            ArmorEnergyBuffer.write(armor, CAP);
            require(hit(player, 400, false).isCanceled(), "first shield failed");
            require(ArmorEnergyBuffer.read(armor) == CAP - 8_000_000 && ehv(player, armor) == 15_584,
                    "small hit was charged the full tier");
        });
        h.runAtTickTime(59, () -> {
            ArmorCapabilityCollector.clearCache(player);
            require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "death did not share shield combo");
            require(ArmorEnergyBuffer.read(armor) == CAP - 2_000_000_000L && ehv(player, armor) == 15_360,
                    "first death after shielding did not stay at tier 1");
        });
        h.runAtTickTime(60, () -> {
            ArmorCapabilityCollector.clearCache(player);
            require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "new death window failed");
            require(ArmorEnergyBuffer.read(armor) == CAP - 4_000_000_000L && ehv(player, armor) == 14_336,
                    "late combo moved the deadline or failed to reset to tier 1");
            h.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void failedPaymentLeavesNoCreditAndNoPartialDebit(GameTestHelper h) {
        var armor = armor(h, ModItems.CELESTWEAVE_SUBMODULE_PHASE_SHIELD.get());
        var player = equip(h, armor);
        network(h, armor, 1);
        h.runAtTickTime(40, () -> {
            require(!hit(player, 10, false).isCanceled(), "insufficient EHV blocked damage");
            require(ArmorEnergyBuffer.read(armor) == CAP && ehv(player, armor) == 1, "failed payment took resources");
            storage(player, armor).insert(LightningKey.EXTREME_HIGH_VOLTAGE, 19, Actionable.MODULATE, ACTION);
            ArmorEnergyBuffer.write(armor, 0);
            require(!hit(player, 10, false).isCanceled(), "insufficient FE blocked damage");
            require(ehv(player, armor) == 20, "failed FE payment consumed EHV");
            ArmorEnergyBuffer.write(armor, 200_000);
            require(hit(player, 10, false).isCanceled(), "funded shield failed");
            require(ArmorEnergyBuffer.read(armor) == 0 && ehv(player, armor) == 0,
                    "unpaid hit granted free window credit");
            h.succeed();
        });
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void multidimensionalIsFreeAndEveryShieldPairConflicts(GameTestHelper h) {
        var tiers = List.of(ModItems.CELESTWEAVE_SUBMODULE_MATRIX_SHIELD.get(),
                ModItems.CELESTWEAVE_SUBMODULE_PHASE_SHIELD.get(),
                ModItems.CELESTWEAVE_SUBMODULE_OVERLOAD_PROTECTION.get(),
                ModItems.CELESTWEAVE_SUBMODULE_MULTIDIMENSIONAL_PROTECTION.get());
        for (Item first : tiers) {
            for (Item second : tiers) {
                var armor = armor(h, first);
                require(!CelestweaveArmorState.installOneModule(armor, h.getLevel().registryAccess(),
                        new ItemStack(second)), "shield pair installed together: " + first + "/" + second);
            }
        }
        var armor = armor(h, ModItems.CELESTWEAVE_SUBMODULE_MULTIDIMENSIONAL_PROTECTION.get());
        ArmorEnergyBuffer.write(armor, 0);
        var player = equip(h, armor);
        ArmorTickService.tickEquipped(player, armor, true, h.getLevel().registryAccess(), Dist.DEDICATED_SERVER);
        require(hit(player, Float.MAX_VALUE, true).isCanceled(), "free shield needs resources");
        require(CelestweaveArmorUndyingHandler.tryProtectForcedDeath(player), "free death protection needs resources");
        require(ArmorEnergyBuffer.read(armor) == 0, "free protection changed FE");
        h.succeed();
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void oldStackAndArmorNbtMigrateWithoutLosingSettings(GameTestHelper h) {
        var registries = h.getLevel().registryAccess();
        var oldItem = new ItemStack(ModItems.CELESTWEAVE_SUBMODULE_OVERLOAD_PROTECTION.get(), 3);
        oldItem.setHoverName(Component.literal("legacy module"));
        var oldTag = (CompoundTag) oldItem.save(new CompoundTag());
        oldTag.putString("id", "ae2lt:module_undying");
        var migratedItem = ItemStack.of(oldTag);
        require(migratedItem.is(ModItems.CELESTWEAVE_SUBMODULE_OVERLOAD_PROTECTION.get())
                && migratedItem.getCount() == 3 && migratedItem.getHoverName().getString().equals("legacy module"),
                "old item id/count/components did not migrate");

        var chest = armor(h, ModItems.CELESTWEAVE_SUBMODULE_PHASE_SHIELD.get());
        var armorId = CelestweaveArmorState.ensureArmorId(chest);
        var raw = (CompoundTag) chest.save(new CompoundTag());
        var component = raw.getCompound("tag").getCompound("ae2lt:celestweave_modules");
        ListTag modules = component.getList("modules", 10);
        var installedOld = oldTag.copy();
        installedOld.putByte("Count", (byte) 1);
        modules.add(installedOld);
        var toggles = new CompoundTag();
        toggles.putBoolean("undying", false);
        component.put("toggles", toggles);
        var data = new CompoundTag();
        var legacy = new CompoundTag();
        legacy.putInt("ComboCount", 7);
        legacy.putLong("ComboUntil", 12345);
        var options = new CompoundTag();
        options.putBoolean("hit_feedback", false);
        legacy.put("Options", options);
        data.put("undying", legacy);
        component.put("submodule_data", data);

        var migrated = ItemStack.of(raw);
        var container = ModDataComponents.CELESTWEAVE_MODULES.get(migrated);
        require(container != null && container.armorId().orElseThrow().equals(armorId), "armor identity lost");
        require(container.modules().size() == 2, "phase was not absorbed or energy module was lost");
        require(container.modules().stream().noneMatch(s -> s.is(ModItems.CELESTWEAVE_SUBMODULE_PHASE_SHIELD.get())),
                "legacy phase survived overload migration");
        require(!container.toggles().get("overload_protection") && !container.toggles().containsKey("undying"),
                "disabled state was not preserved");
        require(container.submoduleData().get("overload_protection").equals(legacy), "legacy data lost");
        require(ArmorEnergyBuffer.read(migrated) == CAP, "energy buffer lost");
        String saved = migrated.save(new CompoundTag()).toString();
        require(!saved.contains("module_undying") && !saved.contains("undying:"), "legacy id was written back");
        var reloaded = ItemStack.of(migrated.save(new CompoundTag()));
        require(migrated.save(new CompoundTag()).equals(reloaded.save(new CompoundTag())), "migration was not idempotent");
        toggles.putBoolean("overload_protection", true);
        var canonical = new CompoundTag();
        canonical.putInt("ComboCount", 9);
        data.put("overload_protection", canonical);
        var mixed = ModDataComponents.CELESTWEAVE_MODULES.get(ItemStack.of(raw));
        require(mixed.toggles().get("overload_protection"), "new toggle lost to legacy toggle");
        var mergedData = mixed.submoduleData().get("overload_protection");
        require(mergedData.getInt("ComboCount") == 9 && mergedData.getLong("ComboUntil") == 12345,
                "new values must win while old unknown data survives");
        require(legacy.getInt("ComboCount") == 7, "migration mutated input data");
        var phase = new ItemStack(ModItems.CELESTWEAVE_SUBMODULE_PHASE_SHIELD.get());
        require(ItemStack.of(phase.save(new CompoundTag())).is(phase.getItem()),
                "standalone phase item was consumed");
        h.succeed();
    }

    @GameTest(templateNamespace = "ae2lt", template = "workstation_test")
    public static void legacyResearchNotesStillRequestTheRenamedRitualItem(GameTestHelper h) {
        var ids = new java.util.ArrayList<net.minecraft.resources.ResourceLocation>();
        ids.add(new net.minecraft.resources.ResourceLocation("ae2lt:pigmee_core"));
        ids.add(new net.minecraft.resources.ResourceLocation("ae2lt:module_undying"));
        ids.add(new net.minecraft.resources.ResourceLocation("ae2lt:module_phase_lock"));
        for (String id : List.of("stone", "dirt", "sand", "gravel", "apple", "diamond")) {
            ids.add(new net.minecraft.resources.ResourceLocation(id));
        }
        var descriptions = ids.stream().map(id -> "item." + id.getNamespace() + "." + id.getPath()).toList();
        var seed = UUID.randomUUID();
        var note = new ItemStack(ModItems.RESEARCH_NOTE.get());
        new com.moakiee.ae2lt.logic.research.ResearchNoteData(seed,
                com.moakiee.ae2lt.logic.research.RitualGoal.HYPERDIMENSIONAL_PIGMEE,
                ids, descriptions, true).writeTo(note);
        var migrated = com.moakiee.ae2lt.logic.research.ResearchNoteData.read(note);
        require(migrated != null && migrated.ritualSeed().equals(seed) && migrated.consumed(),
                "note seed/completion changed");
        require(migrated.recipeItems().get(1).toString().equals("ae2lt:module_overload_protection"),
                "old note cannot match the renamed item in ritual comparisons");
        require(migrated.descriptionKeys().get(1).equals("item.ae2lt.module_overload_protection"),
                "old note still renders an obsolete translation key");
        require(migrated.recipeItems().subList(2, 9).equals(ids.subList(2, 9)), "ritual order changed");
        com.moakiee.ae2lt.item.ResearchNoteItem.applyGeneratedState(note, migrated);
        require(!note.getOrCreateTag().toString().contains("module_undying"),
                "reopening the note did not save canonical IDs");
        h.succeed();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }
}
