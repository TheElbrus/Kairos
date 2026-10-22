package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 1.4 L0: fences, walls and panes take their collision shape from the neighbours,
 * not only from the state (BlockFence.java:61-94, BlockWall.java:67-106,
 * BlockPane.java:69-97). No recording is involved: the scenarios are built here, and the
 * expected boxes are the game's own formulas evaluated by hand.
 *
 * <p>Every scenario resolves states from the frozen dump, so this also guards that the
 * schema-2 connector facts (kind/group/cube/pane + the per-state isSideSolid mask) are
 * present and meaningful.
 */
class CollisionWorldConnectorTest {

    @Test
    void fenceLineUsesTheEastWestRail() throws IOException {
        // Fences at x 0..2; the middle and end cells have no north/south arm. The rail is
        // BlockFence.java:154-163: z 0.375..0.625, x extended to the connected side.
        CollisionWorld world = world(
                fence(0) + ", " + fence(1) + ", " + fence(2));

        assertBoxes(world, 1, 0, 0, box(1.0, 0.0, 0.375, 2.0, 1.5, 0.625));
        assertBoxes(world, 0, 0, 0, box(0.375, 0.0, 0.375, 1.0, 1.5, 0.625));
        assertBoxes(world, 2, 0, 0, box(2.0, 0.0, 0.375, 2.625, 1.5, 0.625));
    }

    @Test
    void oakAndNetherBrickFencesDoNotConnect() throws IOException {
        // BlockFence.java:142: only the same Material connects, so both cells are the
        // lone-post fallback box (BlockFence.java:160-163).
        CollisionWorld world = world(
                fence(0) + ", {\"from\": [1, 0, 0], \"to\": [1, 0, 0],"
                        + " \"id\": \"nether_brick_fence\","
                        + " \"props\": {\"east\": \"false\", \"north\": \"false\","
                        + " \"south\": \"false\", \"west\": \"false\"}}");

        assertBoxes(world, 0, 0, 0, box(0.375, 0.0, 0.375, 0.625, 1.5, 0.625));
        assertBoxes(world, 1, 0, 0, box(1.375, 0.0, 0.375, 1.625, 1.5, 0.625));
    }

    @Test
    void oakFenceConnectsToAFullCube() throws IOException {
        // BlockFence.java:145-147: an opaque full cube (not a gourd) extends the rail.
        CollisionWorld world = world(fence(0) + ", " + stone(1));

        assertBoxes(world, 0, 0, 0, box(0.375, 0.0, 0.375, 1.0, 1.5, 0.625));
    }

    @Test
    void straightWallRunNarrowsToTheRail() throws IOException {
        // BlockWall.java:130-133: a west-east run narrows the post to z 0.3125..0.6875;
        // BlockWall.java:103-104 forces the collision height to 1.5.
        CollisionWorld world = world(wall(0) + ", " + wall(1) + ", " + wall(2));

        assertBoxes(world, 1, 0, 0, box(1.0, 0.0, 0.3125, 2.0, 1.5, 0.6875));
    }

    @Test
    void wallConnectsToAGateAndToAFullCube() throws IOException {
        // BlockWall.java:110-113: the same wall block OR any gate OR a full cube.
        CollisionWorld gate = world(wall(0) + ", {\"from\": [1, 0, 0], \"to\": [1, 0, 0],"
                + " \"id\": \"fence_gate\", \"props\": {\"facing\": \"east\","
                + " \"in_wall\": \"false\", \"open\": \"false\","
                + " \"powered\": \"false\"}}");
        assertBoxes(gate, 0, 0, 0, box(0.25, 0.0, 0.25, 1.0, 1.5, 0.75));

        CollisionWorld stone = world(wall(0) + ", " + stone(1));
        assertBoxes(stone, 0, 0, 0, box(0.25, 0.0, 0.25, 1.0, 1.5, 0.75));
    }

    @Test
    void paneConnectsAlongItsLine() throws IOException {
        // A lone pane is the full cross (BlockPane.java:85-87, :98-100); with one south
        // pane it is the north-south half plate only (BlockPane.java:88-95).
        CollisionWorld lone = world(pane(0, 0));
        assertBoxes(lone, 0, 0, 0,
                box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625),
                box(0.4375, 0.0, 0.0, 0.5625, 1.0, 1.0));

        CollisionWorld line = world(pane(0, 0) + ", " + pane(0, 1));
        assertBoxes(line, 0, 0, 0, box(0.4375, 0.0, 0.5, 0.5625, 1.0, 1.0));
        assertBoxes(line, 0, 0, 1, box(0.4375, 0.0, 1.0, 0.5625, 1.0, 1.5));
    }

    @Test
    void paneConnectsToAFullCubeButNotToAWall() throws IOException {
        // BlockPane.java:162-166: pane group or the neighbour's isSideSolid; stone is a
        // full cube, a wall is not full-cube-solid (BlockWall.isFullCube is false).
        CollisionWorld withStone = world(pane(0, 0) + ", " + stone(0, 1));
        assertBoxes(withStone, 0, 0, 0, box(0.4375, 0.0, 0.5, 0.5625, 1.0, 1.0));

        CollisionWorld withWall = world(pane(0, 0) + ", "
                + "{\"from\": [1, 0, 0], \"to\": [1, 0, 0],"
                + " \"id\": \"cobblestone_wall\","
                + " \"props\": {\"east\": \"false\", \"north\": \"false\","
                + " \"south\": \"false\", \"up\": \"false\","
                + " \"variant\": \"cobblestone\", \"west\": \"false\"}}");
        assertBoxes(withWall, 0, 0, 0,
                box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625),
                box(0.4375, 0.0, 0.0, 0.5625, 1.0, 1.0));
    }

    // --- helpers ---------------------------------------------------------------

    private static String fence(int x) {
        return "{\"from\": [" + x + ", 0, 0], \"to\": [" + x + ", 0, 0],"
                + " \"id\": \"fence\","
                + " \"props\": {\"east\": \"false\", \"north\": \"false\","
                + " \"south\": \"false\", \"west\": \"false\"}}";
    }

    private static String wall(int x) {
        return "{\"from\": [" + x + ", 0, 0], \"to\": [" + x + ", 0, 0],"
                + " \"id\": \"cobblestone_wall\","
                + " \"props\": {\"east\": \"false\", \"north\": \"false\","
                + " \"south\": \"false\", \"up\": \"false\","
                + " \"variant\": \"cobblestone\", \"west\": \"false\"}}";
    }

    private static String pane(int x, int z) {
        return "{\"from\": [" + x + ", 0, " + z + "], \"to\": [" + x + ", 0, " + z + "],"
                + " \"id\": \"glass_pane\","
                + " \"props\": {\"east\": \"false\", \"north\": \"false\","
                + " \"south\": \"false\", \"west\": \"false\"}}";
    }

    private static String stone(int x) {
        return stone(x, 0);
    }

    private static String stone(int x, int z) {
        return "{\"from\": [" + x + ", 0, " + z + "], \"to\": [" + x + ", 0, " + z + "],"
                + " \"id\": \"stone\", \"props\": {\"variant\": \"stone\"}}";
    }

    private static double[] box(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Every box of the cell, in world coordinates, via a query of the cell interior. */
    private static void assertBoxes(CollisionWorld world, int x, int y, int z,
            double[]... expected) {
        world.query(x + 0.001, y + 0.001, z + 0.001,
                x + 0.999, y + 0.999, z + 0.999);
        List<double[]> actual = new ArrayList<double[]>();
        for (int i = 0; i < world.queryCount(); i++) {
            double[] bounds = new double[6];
            world.boxBounds(world.queryBox(i), bounds);
            actual.add(bounds);
        }
        assertEquals(expected.length, actual.size(),
                "box count at (" + x + "," + y + "," + z + ")");
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual.get(i), 0.0,
                    "box " + i + " at (" + x + "," + y + "," + z + ")");
        }
    }

    private static CollisionWorld world(String blocks) throws IOException {
        String json = "{\"name\": \"connector_l0\","
                + " \"start\": {\"pos\": [0.5, 1.0, 0.5]},"
                + " \"blocks\": [" + blocks + "],"
                + " \"inputs\": [{\"ticks\": 1}]}";
        return CollisionWorld.of(
                BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json")),
                ScenarioParser.parse(json));
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return f;
            }
        }
        throw new IllegalStateException("no " + ScenarioLocator.ROOT_MARKER + " above " + dir);
    }
}
