package com.moakiee.ae2lt.logic.railgun;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.StorageCells;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import com.moakiee.ae2lt.item.railgun.*;
import com.moakiee.ae2lt.me.key.LightningKey;
import com.moakiee.ae2lt.menu.hub.DeviceHubMenu;
import com.moakiee.ae2lt.menu.hub.DeviceStatusModel;
import com.moakiee.ae2lt.network.hub.DeviceHubSyncPacket;
import com.moakiee.ae2lt.registry.ModDataComponents;
import com.moakiee.ae2lt.registry.ModDamageTypes;
import com.moakiee.ae2lt.registry.ModItems;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Native entity, event, codec, module and ME-storage tests; excluded from release jars. */
@GameTestHolder("ae2lt_railgun")
@PrefixGameTestTemplate(false)
public final class RailgunCombatGameTests {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void close(double actual, double expected, String label) {
        check(Math.abs(actual - expected) < .01, label + ": " + actual + " != " + expected);
    }
    private static ServerPlayer player(GameTestHelper helper) {
        var p = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "RailgunQA"));
        p.getInventory().clearContent();
        return p;
    }
    private static ItemStack gun(ServerPlayer player, boolean overload, boolean multi, boolean beam) {
        var stack = new ItemStack(ModItems.ELECTROMAGNETIC_RAILGUN.get());
        RailgunStructuralCore.setCore(stack, new ItemStack(ModItems.ULTIMATE_OVERLOAD_CORE.get()));
        var storage = RailgunModuleStorage.INSTANCE;
        check(storage.installOne(stack, new ItemStack(ModItems.ENERGY_MODULE_T3.get())), "install energy");
        check(storage.installOne(stack, new ItemStack(ModItems.RAILGUN_MODULE_CORE.get())), "install core");
        if (overload) check(storage.installOne(stack, new ItemStack(ModItems.RAILGUN_MODULE_OVERLOAD_EXECUTION.get())), "install overload");
        if (multi) check(storage.installOne(stack, new ItemStack(ModItems.RAILGUN_MODULE_MULTIDIMENSIONAL_EXECUTION.get())), "install multidimensional");
        if (beam) check(storage.installOne(stack, new ItemStack(ModItems.RAILGUN_MODULE_EHV_BEAM.get())), "beam coexists with execution");
        ModDataComponents.RAILGUN_SETTINGS.set(stack, RailgunSettings.DEFAULT
                .withExecutionMode(RailgunExecutionMode.PERCENTAGE).withEhvBeam(beam).withChainDamage(false).withSound(false));
        RailgunEnergyBuffer.write(stack, 200_000_000L);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack;
    }
    private static Cow target(GameTestHelper helper, BlockPos pos) {
        var cow = new Cow(EntityType.COW, helper.getLevel());
        cow.setNoAi(true);
        cow.setNoGravity(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
        cow.setHealth(cow.getMaxHealth());
        cow.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        helper.getLevel().addFreshEntity(cow);
        return cow;
    }
    private static void hit(GameTestHelper helper, ServerPlayer player, ItemStack gun, LivingEntity target,
                            RailgunChargeTier tier, double base, boolean chain, boolean beam, int duplicates) throws Exception {
        var scopeClass = Class.forName(RailgunFireService.class.getName() + "$ExecutionScope");
        var ctor = scopeClass.getDeclaredConstructor(Vec3.class, double.class, int.class);
        ctor.setAccessible(true);
        Object scope = ctor.newInstance(target.position(), 0D, target.getId());
        var apply = RailgunFireService.class.getDeclaredMethod("applyAll", ServerLevel.class, ServerPlayer.class,
                List.class, DamageContext.class, ItemStack.class, RailgunChargeTier.class, scopeClass);
        apply.setAccessible(true);
        var context = new DamageContext(base, .8, .92, 0, 0, 0, tier.isMax(), beam, false);
        var one = new RailgunChainResolver.Hit(target, base, false, false, null, chain);
        apply.invoke(null, helper.getLevel(), player, java.util.Collections.nCopies(duplicates, one), context, gun, tier, scope);
    }

    @GameTest(templateNamespace = "ae2lt_railgun", template = "empty", timeoutTicks = 100)
    public static void replacingPercentageModuleKeepsMultidimensionalExecution(GameTestHelper helper) throws Exception {
        var p = player(helper);
        var g = gun(p, true, false, false);
        var workbench = com.moakiee.ae2lt.blockentity.workbench.RailgunWorkbenchAdapter.INSTANCE;
        var removed = workbench.uninstallOne(g, helper.getLevel().registryAccess(), "overload_execution");
        check(removed.is(ModItems.RAILGUN_MODULE_OVERLOAD_EXECUTION.get()), "remove overload module");
        check(workbench.installOne(g, helper.getLevel().registryAccess(),
                new ItemStack(ModItems.RAILGUN_MODULE_MULTIDIMENSIONAL_EXECUTION.get())), "install multidimensional module");
        check(ModDataComponents.RAILGUN_SETTINGS.get(g).executionMode() == RailgunExecutionMode.PERCENTAGE,
                "fixture must retain the saved percentage setting across the module swap");
        var victim = target(helper, helper.absolutePos(new BlockPos(3, 2, 4)));
        hit(helper, p, g, victim, RailgunChargeTier.EHV3, 1, false, false, 1);
        check(!victim.isAlive(), "saved percentage mode must not suppress multidimensional execution");
        helper.succeed();
    }

    @GameTest(templateNamespace = "ae2lt_railgun", template = "empty", timeoutTicks = 100)
    public static void chargedPercentagesAreOrdinaryDamageAndNeverDoubleApply(GameTestHelper helper) throws Exception {
        var p = player(helper);
        var g = gun(p, true, false, true);
        var victim = target(helper, helper.absolutePos(new BlockPos(3, 2, 4)));
        var tiers = new RailgunChargeTier[] {RailgunChargeTier.EHV1, RailgunChargeTier.EHV2, RailgunChargeTier.EHV3};
        var fractions = new double[] {.05, .10, .20};
        for (int i = 0; i < 3; i++) {
            victim.setHealth(victim.getMaxHealth());
            long beforeFe = RailgunEnergyBuffer.read(g);
            hit(helper, p, g, victim, tiers[i], 10, false, false, 1);
            close(victim.getHealth(), victim.getMaxHealth() * (1 - fractions[i]) - 10, "tier " + tiers[i]);
            check(RailgunEnergyBuffer.read(g) == beforeFe - 20_000_000L, "same overload surcharge");
        }
        victim.setHealth(victim.getMaxHealth());
        long before = RailgunEnergyBuffer.read(g);
        hit(helper, p, g, victim, RailgunChargeTier.EHV3, 10, false, false, 2);
        close(victim.getHealth(), victim.getMaxHealth() * .8 - 20, "overlapping impacts add percentage only once");
        check(RailgunEnergyBuffer.read(g) == before - 20_000_000L, "overlap only pays once");
        Consumer<LivingHurtEvent> limiter = event -> {
            if (event.getEntity() == victim) event.setAmount(1);
        };
        MinecraftForge.EVENT_BUS.addListener(limiter);
        try {
            victim.setHealth(victim.getMaxHealth());
            hit(helper, p, g, victim, RailgunChargeTier.EHV3, 10, false, false, 1);
            close(victim.getHealth(), victim.getMaxHealth() - 1, "ordinary event can limit percentage damage");
        } finally { MinecraftForge.EVENT_BUS.unregister(limiter); }
        victim.setInvulnerable(true);
        victim.setHealth(victim.getMaxHealth());
        hit(helper, p, g, victim, RailgunChargeTier.EHV3, 10, false, false, 1);
        close(victim.getHealth(), victim.getMaxHealth(), "percentage mode respects entity immunity");
        System.out.println("RAILGUN_PERCENT_PASS tiers=5/10/20 duplicate=once damage_event=honored immunity=honored");
        helper.succeed();
    }

    @GameTest(templateNamespace = "ae2lt_railgun", template = "empty", timeoutTicks = 100)
    public static void modesModulesAndSettingsStayIndependent(GameTestHelper helper) throws Exception {
        var p = player(helper);
        var g = gun(p, true, false, true);
        var victim = target(helper, helper.absolutePos(new BlockPos(3, 2, 4)));
        long fe = RailgunEnergyBuffer.read(g);
        hit(helper, p, g, victim, RailgunChargeTier.EHV3, 10, true, false, 1);
        close(victim.getHealth(), victim.getMaxHealth() - 10, "chain has no percentage");
        hit(helper, p, g, victim, RailgunChargeTier.HV, 10, false, true, 1);
        close(victim.getHealth(), victim.getMaxHealth() - 20, "beam has no percentage");
        check(RailgunEnergyBuffer.read(g) == fe, "excluded paths pay no surcharge");
        check(!RailgunModuleStorage.INSTANCE.canInstallOne(g, new ItemStack(ModItems.RAILGUN_MODULE_EHV_BEAM.get())), "one beam module maximum");
        check(!RailgunModuleStorage.INSTANCE.canInstallOne(g, new ItemStack(ModItems.RAILGUN_MODULE_MULTIDIMENSIONAL_EXECUTION.get())), "original execution module conflict retained");
        var settings = ModDataComponents.RAILGUN_SETTINGS.get(g);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeItem(g);
            var copy = buf.readItem();
            check(ModDataComponents.RAILGUN_SETTINGS.get(copy).equals(settings), "item keeps settings");
            check(RailgunModuleStorage.entryData(copy).hasEhvBeam(), "item keeps beam module");
        } finally { buf.release(); }
        var menu = new DeviceHubMenu(19, p.getInventory(), DeviceHubMenu.TAB_RAILGUN);
        p.containerMenu = menu;
        menu.toggleRailgunEhvBeam();
        check(!ModDataComponents.RAILGUN_SETTINGS.get(g).ehvBeamEnabled(), "server beam toggle");
        check(ModDataComponents.RAILGUN_SETTINGS.get(g).executionMode() == RailgunExecutionMode.PERCENTAGE, "beam toggle preserves execution mode");
        var remembered = new net.minecraft.nbt.CompoundTag();
        remembered.put("OverloadExecutionTargets", new net.minecraft.nbt.ListTag());
        remembered.putString("unrelated", "kept");
        g.getOrCreateTag().merge(remembered);
        menu.cycleRailgunExecutionMode();
        check(!g.getOrCreateTag().contains("OverloadExecutionTargets"), "leaving percentage clears old record");
        check(g.getOrCreateTag().getString("unrelated").equals("kept"), "unrelated item data preserved");
        check(ModDataComponents.RAILGUN_SETTINGS.get(g).executionMode() == RailgunExecutionMode.NORMAL, "percentage goes to normal");
        var status = DeviceStatusModel.fromRailgunStack(g, p);
        var packetMethod = DeviceHubMenu.class.getDeclaredMethod("toSyncPacket", DeviceStatusModel.class);
        packetMethod.setAccessible(true);
        var packet = (DeviceHubSyncPacket) packetMethod.invoke(menu, status);
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(wire);
            check(packet.equals(DeviceHubSyncPacket.decode(wire)) && wire.readableBytes() == 0, "hub packet round trip");
        } finally { wire.release(); }
        g.getOrCreateTag().merge(remembered);
        menu.cycleRailgunExecutionMode();
        check(g.getOrCreateTag().contains("OverloadExecutionTargets"), "normal-to-forced preserves existing tracking");
        var workbench = com.moakiee.ae2lt.blockentity.workbench.RailgunWorkbenchAdapter.INSTANCE;
        var detached = workbench.uninstallOne(g, helper.getLevel().registryAccess(), "ehv_beam");
        check(detached.is(ModItems.RAILGUN_MODULE_EHV_BEAM.get()), "workbench removes EHV module");
        check(workbench.moduleInputValidator(g, helper.getLevel().registryAccess()).test(detached), "workbench accepts EHV module");
        check(workbench.installOne(g, helper.getLevel().registryAccess(), detached), "workbench reinstalls EHV module");
        check(helper.getLevel().getRecipeManager().byKey(new net.minecraft.resources.ResourceLocation(
                "ae2lt", "lightning_assembly/railgun_module_ehv_beam")).isPresent(), "EHV module recipe loads");
        var multi = gun(p, false, true, true);
        var multiStatus = DeviceStatusModel.fromRailgunStack(multi, p);
        check(multiStatus.executionMode() == RailgunExecutionMode.NORMAL, "stale percentage setting cannot weaken multidimensional");
        menu.cycleRailgunExecutionMode();
        check(ModDataComponents.RAILGUN_SETTINGS.get(multi).executionMode() == RailgunExecutionMode.FORCED, "multidimensional keeps original cycle");
        System.out.println("RAILGUN_MODES_PASS chain/beam=no_percentage settings/item/hub=roundtrip modules=coexist multi=unchanged");
        helper.succeed();
    }

    @GameTest(templateNamespace = "ae2lt_railgun", template = "empty", timeoutTicks = 160)
    public static void realMeBeamPaysEhvAndHonorsContinuousDamage(GameTestHelper helper) {
        var level = helper.getLevel();
        check(!ModDamageTypes.electromagneticHolder(level).is(DamageTypeTags.BYPASSES_COOLDOWN),
                "ordinary electromagnetic damage keeps hurt cooldown");
        var ehvDamage = ModDamageTypes.electromagneticEhvBeamHolder(level);
        check(ehvDamage.is(DamageTypeTags.BYPASSES_COOLDOWN), "EHV beam bypasses hurt cooldown");
        check(ehvDamage.is(DamageTypeTags.BYPASSES_ARMOR), "EHV beam avoids a second armor reduction");
        check(!ehvDamage.is(DamageTypeTags.BYPASSES_INVULNERABILITY), "EHV beam preserves target immunity");
        level.setWeatherParameters(10000, 0, false, false);
        var p = player(helper);
        var g = gun(p, true, false, true);
        var base = helper.absolutePos(new BlockPos(2, 2, 2));
        var wap = base.north();
        level.setBlockAndUpdate(base, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        level.setBlockAndUpdate(base.east(), AEBlocks.DRIVE.block().defaultBlockState());
        level.setBlockAndUpdate(wap, AEBlocks.WIRELESS_ACCESS_POINT.block().defaultBlockState());
        var cell = new ItemStack(ModItems.LIGHTNING_STORAGE_COMPONENT_I.get());
        var cellStorage = StorageCells.getCellInventory(cell, null);
        var source = IActionSource.ofPlayer(p);
        check(cellStorage.insert(LightningKey.EXTREME_HIGH_VOLTAGE, 48, Actionable.MODULATE, source) == 48, "store EHV");
        check(cellStorage.insert(LightningKey.HIGH_VOLTAGE, 100, Actionable.MODULATE, source) == 100, "store HV");
        cellStorage.persist();
        ((DriveBlockEntity) level.getBlockEntity(base.east())).getInternalInventory().setItemDirect(0, cell);
        g.getOrCreateTag().put("accessPoint", GlobalPos.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, GlobalPos.of(level.dimension(), wap)).result().orElseThrow());
        p.setPos(base.getX() + .5, base.getY() + 1, base.getZ() + .5);
        p.setYRot(0);
        p.setXRot(8);
        var cow = target(helper, base.offset(0, 1, 6));
        helper.runAfterDelay(50, () -> {
            try {
                var bound = RailgunBinding.resolve(g, p);
                check(bound.success(), "real ME access point is active");
                var inventory = bound.grid().getStorageService().getInventory();
                var state = new RailgunBeamService.BeamState(InteractionHand.MAIN_HAND, p.tickCount);
                var count = RailgunBeamService.BeamState.class.getDeclaredField("settleCount");
                count.setAccessible(true);
                var settle = RailgunBeamService.class.getDeclaredMethod("settle", ServerLevel.class,
                        ServerPlayer.class, ItemStack.class, RailgunBeamService.BeamState.class, int.class);
                settle.setAccessible(true);
                long fe = RailgunEnergyBuffer.read(g);
                for (int n = 1; n <= 3; n++) {
                    count.setInt(state, n);
                    check((boolean) settle.invoke(null, level, p, g, state, 5), "EHV settle succeeds");
                    close(cow.getHealth(), cow.getMaxHealth() - 200 * n, "EHV beam ignores ordinary hurt cooldown, no percentage");
                }
                check(cow.invulnerableTime > 10, "EHV damage leaves ordinary hurt cooldown active");
                check(RailgunEnergyBuffer.read(g) == fe - 12_000, "4000 FE per EHV settle");
                check(inventory.extract(LightningKey.EXTREME_HIGH_VOLTAGE, 1000, Actionable.SIMULATE, source) == 0, "16 EHV per settle");
                check(inventory.extract(LightningKey.HIGH_VOLTAGE, 1000, Actionable.SIMULATE, source) == 100, "EHV mode never spends HV");
                check(!(boolean) settle.invoke(null, level, p, g, state, 5), "no EHV stops beam despite HV stock");
                check(RailgunEnergyBuffer.read(g) == fe - 12_000, "failed EHV settle leaves FE intact");
                var settings = ModDataComponents.RAILGUN_SETTINGS.get(g);
                ModDataComponents.RAILGUN_SETTINGS.set(g, settings.withEhvBeam(false));
                count.setInt(state, 8);
                check((boolean) settle.invoke(null, level, p, g, state, 5), "switch back to HV");
                close(cow.getHealth(), cow.getMaxHealth() - 600, "HV respects cooldown immediately after EHV");
                check(RailgunEnergyBuffer.read(g) == fe - 12_400, "HV cost restored");
                check(inventory.extract(LightningKey.HIGH_VOLTAGE, 1000, Actionable.SIMULATE, source) == 99, "HV interval restored");
                cow.invulnerableTime = 0;
                count.setInt(state, 9);
                check((boolean) settle.invoke(null, level, p, g, state, 5), "HV settles after cooldown expires");
                close(cow.getHealth(), cow.getMaxHealth() - 620, "HV fixed damage has no percentage");
                check(cow.invulnerableTime > 10, "HV damage starts ordinary hurt cooldown");
                count.setInt(state, 10);
                check((boolean) settle.invoke(null, level, p, g, state, 5), "HV settles during cooldown");
                close(cow.getHealth(), cow.getMaxHealth() - 620, "consecutive equal HV damage is rejected during cooldown");
                cow.invulnerableTime = 0;
                count.setInt(state, 11);
                check((boolean) settle.invoke(null, level, p, g, state, 5), "HV settles after second cooldown expires");
                close(cow.getHealth(), cow.getMaxHealth() - 640, "HV damage resumes after cooldown");
                check(RailgunEnergyBuffer.read(g) == fe - 13_600, "HV still costs 400 FE per settle including cooldown");
                check(inventory.extract(LightningKey.HIGH_VOLTAGE, 1000, Actionable.SIMULATE, source) == 99, "HV interval remains eight settles");
                ModDataComponents.RAILGUN_SETTINGS.set(g, settings.withEhvBeam(true));
                RailgunModuleStorage.INSTANCE.uninstallOne(g, "ehv_beam");
                check(!RailgunBeamProfile.resolve(RailgunModuleStorage.entryData(g), ModDataComponents.RAILGUN_SETTINGS.get(g)).ehv(), "removing module disables EHV even with stored switch on");
                System.out.println("RAILGUN_BEAM_PASS damage=200x3+20x2 FE=13600 EHV=48 HV=1 insufficient=no_charge percent=absent EHV_cooldown=bypassed HV_cooldown=preserved");
                helper.succeed();
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
    }
}
