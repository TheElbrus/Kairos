package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * L0 tests for the sprint state machine and the 10-tick jump cooldown, the transitions
 * the flat goldens never touch (task 1.5): the double-tap without the sprint key, the
 * key-held start, the same-tick stop on W release and on the previous tick's horizontal
 * collision, the 7-tick window expiry, and a held jump blocked by {@code jumpTicks}.
 *
 * <p>Every expected value is read from the rule, not from the sim: the sprint decisions
 * are EntityPlayerSP.java:553-561 and :591-618 (notes section 1.4 steps 13-19), the
 * cooldown is EntityLivingBase.java:1474-1476 and :1513-1525 (notes 1.5 steps 21, 25),
 * and the 600-tick timer is EntityPlayerSP.java:400-404. The world is the same stone
 * layer the L0 movement tests use, built from the frozen dump via the scenario model.
 */
class MovementSimSprintStateTest {

    /** Stone floor (top at y 65) wide and long enough for every path here. */
    private static final CollisionWorld FLAT_WORLD = flatWorld();

    private static CollisionWorld flatWorld() {
        String json = "{\"name\": \"sprint_state_l0\","
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

    /** The settled tick:-1 anchor: resting on the floor, no keys, not sprinting. */
    private static MovementSim.State flatRest() {
        return MovementSim.initialState(0.5, 65.0, 0.5, 0.0, -0.0784000015258789, 0.0,
                true, false, false, true);
    }

    /** W held gives moveForward 1.0f (MovementInputFromOptions.java:25-28, yaw 0). */
    private static TraceInput input(int w, int jump, int sprint) {
        return new TraceInput(w, 0, 0, 0, jump, 0, sprint, 0.0f, 0.0f,
                w != 0 ? 1.0f : 0.0f, 0.0f);
    }

    private static void assertExact(String what, double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            fail(what + ": expected " + expected + " but got " + actual);
        }
    }

    @Test
    void heldSprintKeyStartsWithTheFirstPress() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 1), FLAT_WORLD);

        // EntityPlayerSP.java:606-611 else branch: the key is down, so start immediately.
        assertTrue(s.sprinting);
        assertEquals(600, s.sprintingTicksLeft, "setSprinting sets 600 (:400-404)");
        assertEquals(0, s.sprintToggleTimer, "no double-tap arm when the key starts sprint");

        MovementSim.step(s, input(1, 0, 1), FLAT_WORLD);
        assertEquals(599, s.sprintingTicksLeft, ":553-557 counts one per tick");
        assertTrue(s.sprinting);
    }

    @Test
    void firstPressWithoutSprintKeyArmsTheDoubleTapWindow() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 0), FLAT_WORLD);

        // EntityPlayerSP.java:606-608: only the 7-tick window starts, no sprint.
        assertFalse(s.sprinting);
        assertEquals(7, s.sprintToggleTimer);
    }

    @Test
    void secondPressInsideTheWindowStartsSprint() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 0), FLAT_WORLD);
        assertEquals(7, s.sprintToggleTimer);

        MovementSim.step(s, input(0, 0, 0), FLAT_WORLD);
        assertEquals(6, s.sprintToggleTimer, "the timer counts down one per tick (:559-561)");

        MovementSim.step(s, input(1, 0, 0), FLAT_WORLD);
        // EntityPlayerSP.java:609-611: the second press inside the window starts sprint.
        assertTrue(s.sprinting);
        assertEquals(600, s.sprintingTicksLeft);
    }

    @Test
    void pressAfterTheWindowExpiredOnlyRearms() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 0), FLAT_WORLD);
        assertEquals(7, s.sprintToggleTimer);

        for (int i = 0; i < 7; i++) {
            MovementSim.step(s, input(0, 0, 0), FLAT_WORLD);
        }
        assertEquals(0, s.sprintToggleTimer, "the window expires after seven quiet ticks");

        MovementSim.step(s, input(1, 0, 0), FLAT_WORLD);
        assertFalse(s.sprinting);
        assertEquals(7, s.sprintToggleTimer, "an expired window only re-arms (:606-608)");
    }

    @Test
    void releasingWStopsSprintOnTheSameTick() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 1), FLAT_WORLD);
        assertTrue(s.sprinting);
        double sprintVz = s.vz;

        MovementSim.step(s, input(0, 0, 1), FLAT_WORLD);
        // EntityPlayerSP.java:616-618: moveForward < 0.8 stops the sprint this very tick,
        // so the physics below never sees the 1.3 speed modifier.
        assertFalse(s.sprinting);
        assertEquals(0, s.sprintingTicksLeft);
        assertTrue(s.vz < sprintVz, "no sprint boost on the release tick");
        assertTrue(s.vz > 0.0, "still moving forward while decelerating");
    }

    @Test
    void previousTickHorizontalCollisionStopsSprint() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 1), FLAT_WORLD);
        assertTrue(s.sprinting);

        // moveEntity set this at the end of the previous tick (notes 1.4 step 19).
        s.colH = true;
        MovementSim.step(s, input(1, 0, 1), FLAT_WORLD);

        // Even with W and the sprint key still held, :616-618 stops it; the key-held
        // start at :613-614 runs earlier in the same tick and is then overridden.
        assertFalse(s.sprinting);
        assertEquals(0, s.sprintingTicksLeft);
    }

    @Test
    void previousTickSneakBlocksTheDoubleTapArm() {
        MovementSim.State s = flatRest();
        s.prevSneak = true;     // flag1 is read before the current keys (:592, :606)
        MovementSim.step(s, input(1, 0, 0), FLAT_WORLD);

        assertFalse(s.sprinting);
        assertEquals(0, s.sprintToggleTimer, "a sneaking previous tick never arms the window");
    }

    @Test
    void jumpWithCooldownDoesNotFire() {
        MovementSim.State s = flatRest();
        s.jumpTicks = 5;
        MovementSim.step(s, input(0, 1, 0), FLAT_WORLD);

        // :1474-1476 decrements first; :1516 then requires jumpTicks == 0.
        assertEquals(4, s.jumpTicks);
        assertTrue(s.onGround);
        assertExact("posY stays on the floor", 65.0, s.y);
        assertExact("velY stays the settled gravity value", -0.0784000015258789, s.vy);
    }

    @Test
    void jumpTicksDecrementOncePerTickAndReleaseClearsThem() {
        MovementSim.State s = flatRest();
        s.jumpTicks = 10;

        MovementSim.step(s, input(0, 1, 0), FLAT_WORLD);
        assertEquals(9, s.jumpTicks);
        MovementSim.step(s, input(0, 1, 0), FLAT_WORLD);
        assertEquals(8, s.jumpTicks);

        // :1523-1524: not jumping resets the cooldown outright.
        MovementSim.step(s, input(0, 0, 0), FLAT_WORLD);
        assertEquals(0, s.jumpTicks);
    }

    @Test
    void heldJumpFiresWhenTheCooldownReachesZero() {
        MovementSim.State s = flatRest();
        s.jumpTicks = 1;        // decrement this tick -> 0, then :1516 allows the jump

        MovementSim.step(s, input(0, 1, 0), FLAT_WORLD);

        // jump() = 0.42 and :1519-1521 re-arms the cooldown; gravity and drag follow.
        assertExact("posY after the jump tick", 65.0 + (double) 0.42f, s.y);
        assertExact("velY after the jump tick",
                ((double) 0.42f - 0.08) * (double) 0.98f, s.vy);
        assertFalse(s.onGround);
        assertEquals(10, s.jumpTicks);
    }

    @Test
    void sprintTimerExpiryStopsSprint() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 1), FLAT_WORLD);
        assertTrue(s.sprinting);

        // Set the counter directly instead of simulating 600 ticks: :553-557 decrements
        // it, and reaching 0 calls setSprinting(false).
        s.sprintingTicksLeft = 1;
        MovementSim.step(s, input(1, 0, 0), FLAT_WORLD);

        assertFalse(s.sprinting);
        assertEquals(0, s.sprintingTicksLeft);
    }
}
