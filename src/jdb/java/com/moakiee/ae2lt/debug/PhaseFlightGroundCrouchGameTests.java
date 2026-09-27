package com.moakiee.ae2lt.debug;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.moakiee.ae2lt.celestweave.FlightSneakMovement;
import com.moakiee.ae2lt.celestweave.PhaseFlightMovementGuard;
import com.moakiee.ae2lt.celestweave.PhaseFlightPlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exercises native collision/travel and pose updates with the real flight Mixins installed. */
@GameTestHolder("ae2lt")
@PrefixGameTestTemplate(false)
public final class PhaseFlightGroundCrouchGameTests {
    private static final Vec3 SNEAK_INPUT = new Vec3(0, 0, 0.3 * 0.98);

    @GameTest(template = "pigmee_station_empty")
    public static void stationaryLockedGroundCrouchKeepsContactAndPose(GameTestHelper h) {
        verifyGroundCrouch(h, Vec3.ZERO, true);
    }

    @GameTest(template = "pigmee_station_empty")
    public static void walkingLockedGroundCrouchKeepsContactAndSpeed(GameTestHelper h) {
        verifyGroundCrouch(h, SNEAK_INPUT, false);
    }

    private static void verifyGroundCrouch(GameTestHelper h, Vec3 input, boolean blockForces) {
        var player = fixture(h, true, true);
        PhaseFlightMovementGuard.updatePhaseLockProtection(player, blockForces, blockForces);
        int lostContact = 0;
        int standingFrames = 0;
        double firstStep = -1;
        StringBuilder trace = new StringBuilder();
        try {
            for (int tick = 0; tick < 20; tick++) {
                Vec3 before = player.position();
                descendInput(player);
                player.travel(input);
                player.updatePoseForTest();
                if (!player.onGround()) lostContact++;
                if (player.getPose() != Pose.CROUCHING) standingFrames++;
                trace.append(player.onGround() ? 'G' : 'A')
                        .append(player.getPose() == Pose.CROUCHING ? 'C' : 'S').append(' ');
                h.assertTrue(Math.abs(player.getY() - before.y) < 1.0E-8, "Floor must stop descent");
                double step = player.position().subtract(before).horizontalDistance();
                if (firstStep < 0) firstStep = step;
                h.assertTrue(Math.abs(step - firstStep) < 1.0E-7, "Ground sneak speed must not alternate");
                h.assertTrue(PhaseFlightPlayerState.isFlying(player), "Ground crouch must preserve locked flight intent");
            }
            System.out.println("PHASE_GROUND_CROUCH " + trace);
            h.assertTrue(lostContact == 0 && standingFrames == 0,
                    "Ground crouch flickered: lostContact=" + lostContact + ", standingFrames=" + standingFrames + "; " + trace);
            h.succeed();
        } finally {
            PhaseFlightMovementGuard.clear(player);
        }
    }

    @GameTest(template = "pigmee_station_empty")
    public static void nonFlyingGroundSneakMatchesVanilla(GameTestHelper h) {
        var vanilla = fixture(h, false, false);
        var locked = fixture(h, false, true);
        vanilla.setDeltaMovement(0, -0.08, 0);
        locked.setDeltaMovement(0, -0.08, 0);
        for (int tick = 0; tick < 20; tick++) {
            vanilla.travel(SNEAK_INPUT);
            locked.travel(SNEAK_INPUT);
            vanilla.updatePoseForTest();
            locked.updatePoseForTest();
            h.assertTrue(vanilla.position().distanceToSqr(locked.position()) < 1.0E-12,
                    "A walking player's flight lock must not change vanilla movement");
            h.assertTrue(locked.onGround() && locked.getPose() == Pose.CROUCHING,
                    "Ordinary ground sneaking must stay crouched and grounded");
        }
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void airborneCrouchChordStillHoversWithoutInertia(GameTestHelper h) {
        var player = fixture(h, true, true);
        player.setPos(player.getX(), player.getY() + 3, player.getZ());
        player.setOnGround(false);
        PhaseFlightPlayerState.setJumpHeld(player, true);
        double y = player.getY();
        player.setDeltaMovement(0.4, -0.2, 0.3);
        player.travel(SNEAK_INPUT);
        h.assertTrue(Math.abs(player.getY() - y) < 1.0E-8, "Shift+space must still cancel vertical inertia in the air");
        Vec3 stoppedAt = player.position();
        player.travel(Vec3.ZERO);
        h.assertTrue(player.position().distanceToSqr(stoppedAt) < 1.0E-12
                        && player.getDeltaMovement().equals(Vec3.ZERO),
                "Releasing direction input must stop precision hover immediately");
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void airborneShiftStillDescends(GameTestHelper h) {
        var player = fixture(h, true, true);
        player.setPos(player.getX(), player.getY() + 3, player.getZ());
        player.setOnGround(false);
        double y = player.getY();
        descendInput(player);
        player.travel(Vec3.ZERO);
        h.assertTrue(player.getY() < y && !player.onGround(), "Shift in free air must descend without artificial ground contact");
        h.succeed();
    }

    @GameTest(template = "pigmee_station_empty")
    public static void leavingTheFloorDoesNotForceGroundContact(GameTestHelper h) {
        var player = fixture(h, true, true);
        Vec3 edge = Vec3.atLowerCornerOf(h.absolutePos(BlockPos.ZERO)).add(2.5, 1, 5.28);
        player.setPos(edge.x, edge.y, edge.z);
        player.setOnGround(true);
        descendInput(player);
        player.travel(SNEAK_INPUT);
        h.assertTrue(player.getBoundingBox().minZ > h.absolutePos(BlockPos.ZERO).getZ() + 5,
                "Fixture must move fully beyond the supporting floor");
        // Native collision resolves Y before horizontal movement, so the crossing step can
        // still contact the floor. The following step must lose contact without a forced flag.
        descendInput(player);
        player.travel(SNEAK_INPUT);
        h.assertTrue(player.getY() < edge.y && !player.onGround(),
                "Native collision must drop ground contact after crossing the edge");
        h.assertTrue(!FlightSneakMovement.isActive(player), "Ground-only precision mode must end off the edge");
        h.succeed();
    }

    private static ProbePlayer fixture(GameTestHelper h, boolean flying, boolean controlled) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        var player = new ProbePlayer(h.getLevel(), h.absolutePos(new BlockPos(2, 1, 2)));
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 2)));
        player.setPos(start.x, start.y, start.z);
        player.setOnGround(true);
        player.setShiftKeyDown(true);
        player.setSpeed(0.1F);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = flying;
        if (controlled) PhaseFlightPlayerState.activate(player);
        player.updatePoseForTest();
        return player;
    }

    private static void descendInput(ProbePlayer player) {
        // Same downward flight impulse as LocalPlayer.aiStep with shift held and jump released.
        PhaseFlightMovementGuard.runAsSelfMovement(player, () -> player.setDeltaMovement(
                player.getDeltaMovement().add(0, -player.getAbilities().getFlyingSpeed() * 3.0F, 0)));
    }

    private static final class ProbePlayer extends Player {
        ProbePlayer(ServerLevel level, BlockPos pos) {
            super(level, pos, 0, new GameProfile(UUID.randomUUID(), "flight-crouch-test"));
        }

        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return true; }
        @Override public boolean isLocalPlayer() { return true; }
        void updatePoseForTest() { updatePlayerPose(); }
    }
}
