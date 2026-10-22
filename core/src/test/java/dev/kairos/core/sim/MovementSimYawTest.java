package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * L0 axis tests for the non-zero-yaw path (task 1.6): the MathHelper table converts the
 * yaw exactly as Entity.moveFlying does (Entity.java:984-988), and the sprint jump boost
 * uses its own angle expression (EntityLivingBase.java:1229-1231). The expected
 * directions come from the formulas, not from the sim's output; the one reused decimal
 * value is the first walk tick already recorded by the game in the state goldens.
 */
class MovementSimYawTest {

    /** Stone floor (top at y 65) wide and long enough for the short paths here. */
    private static final CollisionWorld FLAT_WORLD = flatWorld();

    private static CollisionWorld flatWorld() {
        String json = "{\"name\": \"yaw_l0\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": [{\"from\": [-8, 64, -8], \"to\": [8, 64, 140],"
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

    /** W is moveForward +1.0f, A is moveStrafe +1.0f (left), yaw in degrees. */
    private static TraceInput input(int w, int a, int jump, int sprint, float yaw) {
        return new TraceInput(w, a, 0, 0, jump, 0, sprint, yaw, 0.0f,
                w != 0 ? 1.0f : 0.0f, a != 0 ? 1.0f : 0.0f);
    }

    private static void assertExact(String what, double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            fail(what + ": expected " + expected + " but got " + actual);
        }
    }

    @Test
    void yawZeroIsUnchanged() {
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 0, 0, 0.0f), FLAT_WORLD);

        // Identical to the first walk tick of the settled-anchor state golden.
        assertExact("posZ", 0.5979999899864197, s.z);
        assertExact("velZ", 0.05350800074768003, s.vz);
        assertExact("posX", 0.5, s.x);
        assertExact("velX", 0.0, s.vx);
    }

    @Test
    void forwardAt33_3MovesNegativeXPositiveZ() {
        // moveFlying: dX = -forward*sin(33.3) < 0, dZ = +forward*cos(33.3) > 0.
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 0, 0, 33.3f), FLAT_WORLD);

        assertTrue(s.vx < 0.0, "vx must be negative, got " + s.vx);
        assertTrue(s.vz > 0.0, "vz must be positive, got " + s.vz);
        assertTrue(s.x < 0.5, "posX must decrease");
        assertTrue(s.z > 0.5, "posZ must increase");
    }

    @Test
    void forwardAt161_9MovesNegativeXNegativeZ() {
        // sin(161.9) > 0 and cos(161.9) < 0: both components are negative.
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(1, 0, 0, 0, 161.9f), FLAT_WORLD);

        assertTrue(s.vx < 0.0, "vx must be negative, got " + s.vx);
        assertTrue(s.vz < 0.0, "vz must be negative, got " + s.vz);
        assertTrue(s.x < 0.5, "posX must decrease");
        assertTrue(s.z < 0.5, "posZ must decrease");
    }

    @Test
    void strafeLeftAt161_9MovesNegativeXPositiveZ() {
        // A is +strafe (TraceInput.java:58-65) and the left direction is (cos, sin):
        // cos(161.9) < 0, sin(161.9) > 0.
        MovementSim.State s = flatRest();
        MovementSim.step(s, input(0, 1, 0, 0, 161.9f), FLAT_WORLD);

        assertTrue(s.vx < 0.0, "vx must be negative, got " + s.vx);
        assertTrue(s.vz > 0.0, "vz must be positive, got " + s.vz);
        assertTrue(s.x < 0.5, "posX must decrease");
        assertTrue(s.z > 0.5, "posZ must increase");
    }

    @Test
    void sprintJumpAtYawZeroAddsNoX() {
        MovementSim.State s = flatRest();
        for (int i = 0; i < 5; i++) {
            MovementSim.step(s, input(1, 0, 0, 1, 0.0f), FLAT_WORLD);
        }
        assertExact("straight sprint keeps vx zero", 0.0, s.vx);

        MovementSim.step(s, input(1, 0, 1, 1, 0.0f), FLAT_WORLD);

        // Same tick as the existing L0 sprint-jump pin: sin(0)=0, cos(0)=1.
        assertExact("vx unchanged by the boost at yaw 0", 0.0, s.vx);
        assertExact("vy after the jump tick", 0.33319999363422365, s.vy);
        assertExact("vz after the jump tick", 0.25835736539284193, s.vz);
        assertFalse(s.onGround);
    }

    @Test
    void sprintJumpAt33_3IsAirborneWithAxisMotion() {
        MovementSim.State s = flatRest();
        for (int i = 0; i < 5; i++) {
            MovementSim.step(s, input(1, 0, 0, 1, 33.3f), FLAT_WORLD);
        }
        assertTrue(s.vx < 0.0);
        assertTrue(s.vz > 0.0);

        MovementSim.step(s, input(1, 0, 1, 1, 33.3f), FLAT_WORLD);

        assertFalse(s.onGround);
        assertExact("vy after the jump tick", 0.33319999363422365, s.vy);
        assertTrue(s.vx < 0.0, "the boost adds -sin(33.3), got vx " + s.vx);
        assertTrue(s.vz > 0.0, "the boost adds +cos(33.3), got vz " + s.vz);
    }
}
