package dev.kairos.core.sim;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 0.5.2: the brute-force gap solver must clear straight gaps of 1..4 blocks,
 * and must refuse a 6-block gap. Every accepted plan is independently replayed through
 * {@link FlatSim} + {@link GapWorld} here -- the test does not trust the solver's own
 * bookkeeping -- and the replay's final tick is checked against the task's landing
 * criterion: on the ground, at exactly the floor top, box strictly overlapping a
 * far-floor column.
 */
class GapSolverTest {

    private static final int GAP_START_BLOCK = 5;
    private static final int LAST_FAR_BLOCK = 12;

    /** Far floor starts at z = 6 (gap 1) .. 9 (gap 4); 6 blocks leave the far edge at z = 12. */
    private static final int MAX_JUMP_START_TICK = 25;

    @Test
    void solvesGapsOfOneToFourBlocks() {
        for (int gapBlocks = 1; gapBlocks <= 4; gapBlocks++) {
            GapWorld world = GapWorld.of(GAP_START_BLOCK, gapBlocks, LAST_FAR_BLOCK);
            Optional<GapSolver.Plan> solved = GapSolver.solve(world, MAX_JUMP_START_TICK);
            assertTrue(solved.isPresent(), "no plan clears a " + gapBlocks + "-block gap ("
                    + world + ") within maxJumpStartTick=" + MAX_JUMP_START_TICK);
            GapSolver.Plan plan = solved.get();

            assertPlanReplaysToLanding(world, plan);

            assertEquals(plan.jumpTick + 11, plan.landTick,
                    "the jump arc lands exactly 11 ticks after the jump press: " + plan);
            assertEquals(plan.landTick - plan.jumpTick, plan.tailTicks,
                    "tailTicks must cover through the landing tick: " + plan);
            assertEquals(plan.jumpTick, plan.sprintTicks,
                    "sprintTicks is the pre-jump tick count, i.e. jumpTick: " + plan);

            GapSolver.Plan again = GapSolver.solve(world, MAX_JUMP_START_TICK).get();
            assertEquals(plan.jumpTick, again.jumpTick, "solve() must be deterministic: " + plan);
            assertEquals(plan.landTick, again.landTick, "solve() must be deterministic: " + plan);
            assertEquals(plan.tailTicks, again.tailTicks, "solve() must be deterministic: " + plan);
            assertEquals(0, Double.compare(plan.landingZ, again.landingZ),
                    "solve() must be deterministic: " + plan + " vs " + again);
        }
    }

    @Test
    void refusesSixBlockGap() {
        GapWorld world = GapWorld.of(GAP_START_BLOCK, 6, LAST_FAR_BLOCK);
        Optional<GapSolver.Plan> solved = GapSolver.solve(world, MAX_JUMP_START_TICK);
        assertFalse(solved.isPresent(),
                "a 6-block gap must not be solvable, but got " + solved.orElse(null)
                        + " for " + world);
    }

    @Test
    void strictFacesAtGapEdges() {
        GapWorld gap = GapWorld.of(5, 1, 12);
        assertTrue(gap.overlapsFloor(4.0, 4.7), "box fully over the near floor");
        assertTrue(gap.overlapsFloor(4.7, 5.3), "box spanning the near edge still touches column 4");
        assertFalse(gap.overlapsFloor(5.0, 5.6),
                "box starting exactly at z=5.0 touches the gap edge, strict overlap does not block");
        assertFalse(gap.overlapsFarFloor(5.0, 6.0),
                "box ending exactly at z=6.0 touches the far edge, strict overlap does not land");
        assertTrue(gap.overlapsFarFloor(5.95, 6.05), "box crossing z=6.0 overlaps the far column 6");
        assertTrue(gap.overlapsFloor(5.95, 6.05), "the far column is floor for support too");

        assertTrue(GapWorld.NONE.overlapsFloor(-100.0, -99.0), "NONE is floor everywhere");
        assertFalse(GapWorld.NONE.overlapsFarFloor(-100.0, 100.0), "NONE has no far floor");
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> GapWorld.of(5, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> GapWorld.of(-1, 1, 12));
        assertThrows(IllegalArgumentException.class, () -> GapWorld.of(5, 1, 5));
        assertThrows(IllegalArgumentException.class, () -> GapSolver.solve(GapWorld.NONE, 5));
        assertThrows(NullPointerException.class, () -> GapSolver.solve(null, 5));
        assertThrows(IllegalArgumentException.class,
                () -> GapSolver.solve(GapWorld.of(5, 1, 12), -1));
        assertThrows(IllegalArgumentException.class,
                () -> GapSolver.solve(GapWorld.of(5, 1, 12), 28));
    }

    /**
     * Independent replay: rebuilds the plan's inputs on a fresh anchor state and asserts
     * the task's landing criterion at {@code plan.landTick}, and that the tick before the
     * landing is still airborne (so the landing is not vacuous).
     */
    private static void assertPlanReplaysToLanding(GapWorld world, GapSolver.Plan plan) {
        FlatSim.State s = anchor();
        int tick = 0;
        for (int i = 0; i < plan.sprintTicks; i++, tick++) {
            FlatSim.step(s, GapSolver.SPRINT, world);
        }
        FlatSim.step(s, GapSolver.SPRINT_JUMP, world);
        tick++;
        for (int i = 0; i < plan.tailTicks - 1; i++, tick++) {
            FlatSim.step(s, GapSolver.SPRINT, world);
        }
        String where = "replay of " + plan + " on " + world;
        assertEquals(plan.landTick, tick,
                where + ": tailTicks must cover through landTick, replay is one tick short");
        if (!(s.y > GapWorld.FLOOR_TOP && !s.onGround)) {
            fail(where + ": expected to still be airborne one tick before landTick, got y="
                    + s.y + " onGround=" + s.onGround);
        }

        FlatSim.step(s, GapSolver.SPRINT, world);
        if (!s.onGround) {
            fail(where + ": not onGround at landTick " + plan.landTick + " (y=" + s.y + ")");
        }
        if (Double.compare(GapWorld.FLOOR_TOP, s.y) != 0) {
            fail(where + ": expected y == " + GapWorld.FLOOR_TOP + " exactly at landTick, got " + s.y);
        }
        if (!world.overlapsFarFloor(s.minZ, s.maxZ)) {
            fail(where + ": box [" + s.minZ + ", " + s.maxZ
                    + "] must strictly overlap a far-floor column " + world.gapEndBlock()
                    + ".." + world.lastFarBlock);
        }
        assertEquals(0, Double.compare(plan.landingZ, s.z),
                where + ": landingZ " + plan.landingZ + " must equal the replayed z " + s.z);
    }

    /** The harness start state: the post-settle anchor all three golden traces record. */
    private static FlatSim.State anchor() {
        return FlatSim.initialState(
                0.5, 65.0, 0.5,
                0.0, -0.0784000015258789, 0.0,
                true, false, false, true);
    }
}
