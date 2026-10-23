package dev.kairos.core.solver;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.5 start-region tests: the candidate grid covers the 1x1 top, every returned
 * start is settled by the exact simulator, and an unsupported top produces no start.
 */
class StartRegionTest {

    private static final double FLOOR_TOP = 65.0;

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void offersTheCentreAndEightInsetStartsSettledOnTheExactSurface() {
        StartRegion region = StartRegion.ofBlockTop(0, 64, 0);
        CollisionWorld world = flatBlockWorld();

        List<StartState> starts = region.startStates(world);

        assertEquals(9, starts.size(), "the 3x3 candidate grid must survive support");
        assertEquals(0.0, starts.get(0).offsetX, 0.0, "the centre must be first");
        assertEquals(0.0, starts.get(0).offsetZ, 0.0, "the centre must be first");
        assertEquals(0.5, starts.get(0).state.x, 0.0, "centre x");
        assertEquals(0.5, starts.get(0).state.z, 0.0, "centre z");
        assertEquals(0, starts.get(0).runupDistance, "a standing start has run-up 0");

        for (StartState start : starts) {
            MovementSim.State state = start.state;
            assertEquals(0, start.runupDistance, "standing start");
            assertTrue(state.onGround, "every start must be settled on the ground");
            assertEquals(FLOOR_TOP, state.y, 0.0, "every start must sit on the exact top");
            assertEquals(0.0, state.vx, 0.0, "standing starts have zero vx");
            assertEquals(0.0, state.vz, 0.0, "standing starts have zero vz");
            assertTrue(state.minX >= region.minX - 1.0e-7, "box west edge inside the block");
            assertTrue(state.maxX <= region.maxX + 1.0e-7, "box east edge inside the block");
            assertTrue(state.minZ >= region.minZ - 1.0e-7, "box north edge inside the block");
            assertTrue(state.maxZ <= region.maxZ + 1.0e-7, "box south edge inside the block");
            assertSettledFixedPoint(state, world);
        }
    }

    @Test
    void anUnsupportedTopProducesNoStart() {
        StartRegion region = StartRegion.ofBlockTop(0, 70, 0);

        List<StartState> starts = region.startStates(flatBlockWorld());

        assertTrue(starts.isEmpty(), "a block top with no floor below it must not start");
    }

    @Test
    void theSurfaceAndFootprintComeFromTheBlockCoordinates() {
        StartRegion region = StartRegion.ofBlockTop(-3, 64, 7);

        assertEquals(65.0, region.surfaceY, 0.0);
        assertEquals(-3.0, region.minX, 0.0);
        assertEquals(-2.0, region.maxX, 0.0);
        assertEquals(7.0, region.minZ, 0.0);
        assertEquals(8.0, region.maxZ, 0.0);
    }

    private static void assertSettledFixedPoint(MovementSim.State state,
            CollisionWorld world) {
        MovementSim.State next = copy(state);
        MovementSim.step(next, TraceInput.zero(), world);
        assertEquals(state.x, next.x, 0.0, "settled x must not move");
        assertEquals(state.y, next.y, 0.0, "settled y must not move");
        assertEquals(state.z, next.z, 0.0, "settled z must not move");
        assertEquals(state.vx, next.vx, 0.0, "settled vx must not move");
        assertEquals(state.vy, next.vy, 0.0, "settled vy must not move");
        assertEquals(state.vz, next.vz, 0.0, "settled vz must not move");
        assertEquals(state.onGround, next.onGround, "settled ground flag");
        assertEquals(state.sprinting, next.sprinting, "settled sprint flag");
        assertFalse(next.sprinting, "a zero-input settle must not start sprinting");
    }

    private static CollisionWorld flatBlockWorld() {
        String json = "{\"name\": \"start_region\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "{\"from\": [0, 64, 0], \"to\": [0, 64, 0],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}"
                + "], \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(BLOCKS, ScenarioParser.parse(json));
    }

    private static BlockData loadBlocks() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                try {
                    return BlockData.load(new File(f, "data/1.8.9/blocks.json"));
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "cannot load data/1.8.9/blocks.json", e);
                }
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static MovementSim.State copy(MovementSim.State source) {
        MovementSim.State copy = new MovementSim.State();
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        copy.vx = source.vx;
        copy.vy = source.vy;
        copy.vz = source.vz;
        copy.onGround = source.onGround;
        copy.sprinting = source.sprinting;
        copy.colH = source.colH;
        copy.colV = source.colV;
        copy.minX = source.minX;
        copy.maxX = source.maxX;
        copy.minZ = source.minZ;
        copy.maxZ = source.maxZ;
        copy.maxY = source.maxY;
        copy.jumpTicks = source.jumpTicks;
        copy.sprintingTicksLeft = source.sprintingTicksLeft;
        copy.sprintToggleTimer = source.sprintToggleTimer;
        copy.moveForward = source.moveForward;
        copy.prevSneak = source.prevSneak;
        copy.jumpMovementFactor = source.jumpMovementFactor;
        return copy;
    }
}
