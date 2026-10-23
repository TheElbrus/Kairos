package dev.kairos.core.solver;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.catalog.CatalogEntry;
import dev.kairos.core.catalog.CatalogReader;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.5 run-up tests: committed catalog entries produce the same candidate positions
 * at every run-up distance, distance 0 is a standing start, positive distances carry
 * simulator-derived momentum, and an unsupported run-up block is skipped.
 */
class RunupStartBuilderTest {

    private static final double FLOOR_TOP = 65.0;

    private static final BlockData BLOCKS = loadBlocks();

    @Test
    void buildsEveryGapEntryAtTheSameCandidatePositionsForAllRunups() {
        for (String id : new String[] {
                "gap1_flat_sprint", "gap2_flat_sprint",
                "gap3_flat_sprint", "gap4_flat_momentum"}) {
            CatalogEntry entry = entry(id);
            CollisionWorld world = gapWorld(entry);
            List<StartState> variants = RunupStartBuilder.build(entry, world);

            assertEquals(36, variants.size(), id + " must build 4 distances x 9 starts");
            List<StartState> standing = new ArrayList<StartState>();
            for (StartState variant : variants) {
                if (variant.runupDistance == 0) {
                    standing.add(variant);
                }
            }
            assertEquals(9, standing.size(), id + " must keep the full standing grid");

            for (int i = 0; i < variants.size(); i++) {
                StartState variant = variants.get(i);
                int expectedDistance = i / 9;
                StartState base = standing.get(i % 9);
                assertEquals(expectedDistance, variant.runupDistance,
                        id + " variants must be ordered by distance");
                assertEquals(base.offsetX, variant.offsetX, 0.0,
                        id + " must use the same X offset at every distance");
                assertEquals(base.offsetZ, variant.offsetZ, 0.0,
                        id + " must use the same Z offset at every distance");
                assertEquals(base.state.x, variant.state.x, 0.0,
                        id + " must use the same X position at every distance");
                assertEquals(base.state.z, variant.state.z, 0.0,
                        id + " must use the same Z position at every distance");
                assertSettledOnStartBlock(variant);
                if (expectedDistance == 0) {
                    assertEquals(0.0, variant.state.vx, 0.0, id + " standing vx");
                    assertEquals(0.0, variant.state.vz, 0.0, id + " standing vz");
                } else {
                    assertEquals(0.0, variant.state.vx, 0.0, id + " straight-run vx");
                    assertTrue(variant.state.vz > 0.0,
                            id + " positive run-up must move toward the goal");
                    assertTrue(variant.state.sprinting,
                            id + " a W+sprint run-up must be sprinting");
                    assertEquals(1.0f, variant.state.moveForward, 0.0f,
                            id + " the run-up must keep W held at the start");
                }
            }

            assertEquals(0.0f, RunupStartBuilder.yawFor(entry), 0.0f,
                    id + " approaches along +Z");
            assertIncreasingMomentum(variants, id);
        }
    }

    @Test
    void unsupportedRunupBlocksAreSkipped() {
        CatalogEntry entry = entry("gap1_flat_sprint");
        CollisionWorld world = isolatedStartWorld(entry);

        List<StartState> variants = RunupStartBuilder.build(entry, world);

        assertEquals(9, variants.size(), "only the standing starts are supported");
        for (StartState variant : variants) {
            assertEquals(0, variant.runupDistance,
                    "no run-up block exists behind the isolated start");
        }
    }

    @Test
    void rejectsADiagonalApproach() {
        CatalogEntry diagonal = new CatalogEntry(
                "diagonal", 0, 64, 0, Arrays.asList(0), 1, 64, 1,
                CatalogEntry.Expect.FEASIBLE, CatalogEntry.LabelSource.WIKI,
                Arrays.asList("gap"));

        assertThrows(IllegalArgumentException.class,
                () -> RunupStartBuilder.build(diagonal, gapWorld(diagonal)));
    }

    private static void assertIncreasingMomentum(List<StartState> variants, String id) {
        double d1 = centreVz(variants, 1);
        double d2 = centreVz(variants, 2);
        double d3 = centreVz(variants, 3);
        assertTrue(d1 > 0.0, id + " distance 1 must have momentum");
        assertTrue(d2 > d1, id + " distance 2 must have more momentum than distance 1");
        assertTrue(d3 > d2, id + " distance 3 must have more momentum than distance 2");
    }

    private static double centreVz(List<StartState> variants, int distance) {
        for (StartState variant : variants) {
            if (variant.runupDistance == distance
                    && Double.compare(variant.offsetX, 0.0) == 0
                    && Double.compare(variant.offsetZ, 0.0) == 0) {
                return variant.state.vz;
            }
        }
        throw new AssertionError("no centre variant for distance " + distance);
    }

    private static void assertSettledOnStartBlock(StartState start) {
        MovementSim.State state = start.state;
        assertTrue(state.onGround, "run-up start must be on the ground");
        assertEquals(FLOOR_TOP, state.y, 0.0, "run-up start must be on the surface");
        assertTrue(state.minX >= start.blockX - 1.0e-7, "box inside the start block");
        assertTrue(state.maxX <= start.blockX + 1.0 + 1.0e-7, "box inside the start block");
        assertTrue(state.minZ >= start.blockZ - 1.0e-7, "box inside the start block");
        assertTrue(state.maxZ <= start.blockZ + 1.0 + 1.0e-7, "box inside the start block");
    }

    private static CatalogEntry entry(String id) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                try {
                    return CatalogReader.read(new File(f, "data/catalog/" + id + ".json"));
                } catch (IOException e) {
                    throw new IllegalStateException("cannot read catalog entry " + id, e);
                }
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static CollisionWorld gapWorld(CatalogEntry entry) {
        int maxRunup = entry.runup.get(entry.runup.size() - 1);
        String json = "{\"name\": \"runup_" + entry.id + "\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "{\"from\": [-2, 64, " + (entry.startZ - maxRunup - 2) + "],"
                + " \"to\": [2, 64, " + entry.startZ + "],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}},"
                + "{\"from\": [-2, 64, " + entry.goalZ + "],"
                + " \"to\": [2, 64, " + (entry.goalZ + 10) + "],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}"
                + "], \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(BLOCKS, ScenarioParser.parse(json));
    }

    private static CollisionWorld isolatedStartWorld(CatalogEntry entry) {
        String json = "{\"name\": \"isolated_" + entry.id + "\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "{\"from\": [0, 64, " + entry.startZ + "],"
                + " \"to\": [0, 64, " + entry.startZ + "],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}},"
                + "{\"from\": [0, 64, " + entry.goalZ + "],"
                + " \"to\": [0, 64, " + entry.goalZ + "],"
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
}
