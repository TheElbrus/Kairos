package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * L0 formula tests for {@link MovementSim} (PLAN.md section 6): every expected value is
 * derived from the published rule - the MCPK wiki pages (Vertical Movement Formulas,
 * Horizontal Movement Formulas, Status Effects) and the worked example in
 * {@code docs/physics-notes.md} - never from the sim's own output. Wiki values that are
 * published rounded are checked at 1e-3; values re-derived here with the game's constant
 * types are pinned at 1e-12; values the existing goldens already pin exactly are
 * compared bit-for-bit.
 *
 * <p>Since task 1.2 the sim needs a collision world. These tests step against
 * {@link #FLAT_WORLD}, a real stone layer built through {@code blocks.json} - the same
 * geometry the 1.1 flat model hard-coded, now queried the way the game does.
 */
class MovementSimL0Test {

    /**
     * A full stone layer (top at y 65, the 1.1 flat world) wide and long enough for
     * every path: x -1..1, z -8..140 (the longest walk in here ends near z 110).
     */
    private static final CollisionWorld FLAT_WORLD = flatWorld();

    private static CollisionWorld flatWorld() {
        String json = "{\"name\": \"l0_flat\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": [{\"from\": [-1, 64, -8], \"to\": [1, 64, 140],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}],"
                + " \"inputs\": [{\"ticks\": 1}]}";
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                try {
                    return CollisionWorld.of(
                            BlockData.load(new File(f, "data/1.8.9/blocks.json")),
                            ScenarioParser.parse(json));
                } catch (IOException e) {
                    throw new IllegalStateException("cannot load data/1.8.9/blocks.json", e);
                }
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    /** The recorded tick:-1 anchor state: settled on the floor, motion clamped by gravity. */
    private static MovementSim.State flatRest() {
        return MovementSim.initialState(0.5, 65.0, 0.5, 0.0, -0.0784000015258789, 0.0,
                true, false, false, true);
    }

    private static TraceInput input(int w, int jump, int sprint, float mf) {
        return new TraceInput(w, 0, 0, 0, jump, 0, sprint, 0.0f, 0.0f, mf, 0.0f);
    }

    private static void assertExact(String what, double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            fail(what + ": expected " + expected + " but got " + actual);
        }
    }

    @Test
    void firstJumpTickMatchesVerticalVelocityFormula() {
        // V_Y,1 = 0.42 m/t (MCPK Vertical Movement Formulas); during the jump tick the
        // player moves by 0.42, then V_Y = (V_Y,1 - 0.08) * 0.98.
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(0, 1, 0, 0.0f), FLAT_WORLD);

        assertExact("posY after the first jump tick", 65.0 + (double) 0.42f, s.y);
        assertExact("V_Y after the first jump tick",
                ((double) 0.42f - 0.08) * (double) 0.98f, s.vy);
        assertEquals(0.33319999363422365, s.vy, 1e-12);
        assertEquals(0.5, s.x);
        assertEquals(0.5, s.z);
        assertEquals(0.0, s.vz);
        assertFalse(s.onGround);
        assertFalse(s.colV);
        assertEquals(10, s.jumpTicks);
    }

    @Test
    void sprintJumpTickFiveMatchesWorkedExample() {
        // docs/physics-notes.md worked example (MCPK sprint jump): replay tick 4 of the
        // solo sprint from the anchor, then press jump while sprinting.
        MovementSim.State s = flatRest();
        for (int i = 0; i < 5; i++) {
            MovementSim.step(s, input(1, 0, 1, 1.0f), FLAT_WORLD);
        }
        assertExact("posZ at the end of sprint tick 4", 1.581978028176667, s.z);
        assertExact("V_HZ at the end of sprint tick 4", 0.1457819489355333, s.vz);
        assertTrue(s.sprinting);

        MovementSim.step(s, input(1, 1, 1, 1.0f), FLAT_WORLD);

        // Published worked-example outputs for the sprint jump tick itself.
        assertExact("posY at the jump tick", 65.41999998688698, s.y);
        assertExact("posZ at the jump tick", 2.0551599611143136, s.z);
        assertExact("V_Y at the jump tick", 0.33319999363422365, s.vy);
        assertExact("V_HZ at the jump tick", 0.25835736539284193, s.vz);
        assertTrue(s.sprinting);
        assertFalse(s.onGround);
    }

    @Test
    void jumpProfileMatchesWikiApexAndDuration() {
        // MCPK: a flat jump lasts 12 ticks and reaches the old (1.8) jump height 1.249.
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(0, 1, 0, 0.0f), FLAT_WORLD);
        double maxY = s.y;
        int landingTick = -1;
        for (int tick = 2; tick <= 40 && landingTick < 0; tick++) {
            MovementSim.step(s, TraceInput.zero(), FLAT_WORLD);
            maxY = Math.max(maxY, s.y);
            if (s.onGround) {
                landingTick = tick;
            }
        }
        assertEquals(12, landingTick, "a flat jump must last 12 ticks inclusive");
        double apex = maxY - 65.0;
        assertEquals(1.2491870787446813, apex, 1e-12);
        assertTrue(Math.abs(apex - 1.249) < 1e-3, "MCPK 1.8 jump height is 1.249");

        // Without the 0.005 threshold the arc would climb to the no-truncation apex,
        // the value MCPK lists as the new (1.9) jump height. The threshold must truncate.
        double ideal = 0.0;
        double vy = (double) 0.42f;
        while (vy > 0.0) {
            ideal += vy;
            vy = (vy - 0.08) * (double) 0.98f;
        }
        assertEquals(1.2522033402537238, ideal, 1e-12);
        assertTrue(Math.abs(ideal - 1.252) < 1e-3, "MCPK 1.9 jump height is 1.252");
        assertTrue(apex < ideal, "the momentum threshold truncates the apex");
    }

    @Test
    void terminalFallVelocityMatchesFormula() {
        // V_Y,t = (V_Y,t-1 - 0.08) * 0.98 eventually locks at the terminal fall speed;
        // MCPK rounds it to -3.92 m/t.
        double v = -0.1;
        for (int i = 0; i < 5000; i++) {
            v = (v - 0.08) * (double) 0.98f;
        }
        assertEquals(-3.9200038147008747, v, 1e-12);
        assertTrue(Math.abs(v + 3.92) < 1e-3, "MCPK terminal velocity is -3.92");

        // The sim's gravity uses the same widened 0.98f constant: stepping at terminal
        // speed must keep V_Y unchanged (the recurrence is at its fixed point).
        MovementSim.State s = MovementSim.initialState(
                0.5, 200.0, 0.5, 0.0, v, 0.0, false, false, false, false);
        MovementSim.step(s, TraceInput.zero(), FLAT_WORLD);
        assertEquals(v, s.vy, 1e-12);
        assertFalse(s.onGround);
    }

    @Test
    void groundTerminalAdvancesMatchWikiMaxSpeeds() {
        // MCPK: max walking speed 0.215 m/t, max sprinting speed 0.280 m/t. On stone the
        // horizontal recurrence locks at a fixed point (measured independently here as
        // 0.21585903893404823 / 0.28061673748548444).
        MovementSim.State walk = flatRest();
        for (int i = 0; i < 380; i++) {
            MovementSim.step(walk, input(1, 0, 0, 1.0f), FLAT_WORLD);
        }
        assertExact("locked walking V_HZ", 0.11785904894762855, walk.vz);
        double walkStart = walk.z;
        for (int i = 0; i < 20; i++) {
            MovementSim.step(walk, input(1, 0, 0, 1.0f), FLAT_WORLD);
        }
        double walkAdvance = (walk.z - walkStart) / 20.0;
        assertEquals(0.21585903893404823, walkAdvance, 1e-9);
        assertTrue(Math.abs(walkAdvance - 0.215) < 1e-3, "MCPK max walking speed is 0.215");

        MovementSim.State sprint = flatRest();
        for (int i = 0; i < 380; i++) {
            MovementSim.step(sprint, input(1, 0, 1, 1.0f), FLAT_WORLD);
        }
        assertExact("locked sprinting V_HZ", 0.15321675646360336, sprint.vz);
        double sprintStart = sprint.z;
        for (int i = 0; i < 20; i++) {
            MovementSim.step(sprint, input(1, 0, 1, 1.0f), FLAT_WORLD);
        }
        double sprintAdvance = (sprint.z - sprintStart) / 20.0;
        assertEquals(0.28061673748548444, sprintAdvance, 1e-9);
        assertTrue(Math.abs(sprintAdvance - 0.280) < 1e-3, "MCPK max sprinting speed is 0.280");
        assertTrue(sprintAdvance > walkAdvance, "sprinting must be faster than walking");
    }

    @Test
    void momentumThresholdZeroesOnlyBelowHalfDelta() {
        // The threshold (EntityLivingBase.java:1492-1500, notes section 2.7) zeroes
        // |v| < 0.005 at the tick start, before movement and before the drag.
        MovementSim.State below = MovementSim.initialState(
                0.5, 70.0, 0.5, 0.0, -0.1, 0.004, false, false, false, false);
        MovementSim.step(below, TraceInput.zero(), FLAT_WORLD);
        assertExact("|V_HZ| = 0.004 must be zeroed", 0.0, below.vz);
        assertExact("z unchanged when V_HZ was zeroed", 0.5, below.z);

        MovementSim.State at = MovementSim.initialState(
                0.5, 70.0, 0.5, 0.0, -0.1, 0.005, false, false, false, false);
        MovementSim.step(at, TraceInput.zero(), FLAT_WORLD);
        assertExact("|V_HZ| = 0.005 survives one tick, then air drag",
                0.005 * (double) 0.91f, at.vz);
        MovementSim.step(at, TraceInput.zero(), FLAT_WORLD);
        assertExact("the dragged 0.00455... is below 0.005 and zeroed next tick",
                0.0, at.vz);
    }

    @Test
    void acceptsYawAndStrafe() {
        // Task 1.6 brought non-axis yaw and strafe into scope (MathHelper table).
        MovementSim.State s = flatRest();
        assertDoesNotThrow(
                () -> MovementSim.step(s, new TraceInput(0, 0, 0, 0, 0, 0, 0, 90.0f, 0.0f, 0.0f, 0.0f),
                        FLAT_WORLD),
                "non-zero yaw is in scope");
        assertDoesNotThrow(
                () -> MovementSim.step(s, new TraceInput(0, 0, 0, 0, 0, 0, 0, 0.0f, 0.0f, 0.0f, 0.5f),
                        FLAT_WORLD),
                "strafe is in scope");
    }
}
