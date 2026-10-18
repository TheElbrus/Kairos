package dev.kairos.core.block;

import dev.kairos.core.scenario.ScenarioLocator;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates the committed block dump {@code data/1.8.9/blocks.json} (task 1.0).
 *
 * <p>The dump is reproduced by the loader's {@code /kairos dumpblocks} from the live
 * 1.8.9 registry (see {@code dev.kairos.loader.BlockDumper}); this test never regenerates
 * it in core. It checks that the file is LF-only, parses through {@link BlockData}, that
 * the header counts equal the contents and the pinned registry totals, and it spot-checks
 * states against expectations derived from {@code mc-src} (every constant below cites the
 * class and method it was read from -- AGENTS.md: physics and block properties are never
 * written from memory).
 *
 * <p>States are found by full property maps (never by index or by guessing extra state),
 * so a registry ordering change cannot make these checks pass vacuously.
 */
class BlockDataDumpTest {

    /**
     * Pinned 1.8.9 totals (Forge 11.15.1.2318, MCP stable_22): the dump was taken from
     * this exact registry and must never change silently.
     */
    private static final int BLOCKS = 198;

    private static final int STATES = 7806;

    private static BlockData dump;

    @Test
    void isLfOnlyAndLoadsWithPinnedCounts() throws IOException {
        BlockData data = dump();
        assertEquals("1.8.9", data.mcVersion());
        assertEquals(BLOCKS, data.blockCount(), "pinned block count");
        assertEquals(STATES, data.stateCount(), "pinned state count");
        assertEquals(data.blockCount(), data.declaredBlockCount(),
                "file header block count vs actual contents");
        assertEquals(data.stateCount(), data.declaredStateCount(),
                "file header state count vs actual contents");
    }

    @Test
    void slabBottomHalfIsYZeroToHalf() throws IOException {
        BlockData data = dump();

        // BlockSlab.java:46-57 setBlockBoundsBasedOnState: HALF=bottom -> (0,0,0,1,0.5,1)
        // (line 55), HALF=top -> (0,0.5,0,1,1,1) (line 53); BlockSlab.java:71-73 primes the
        // bounds before the base box is emitted.
        BlockData.State bottom = data.state("minecraft:stone_slab",
                props("half", "bottom", "variant", "stone"));
        assertNotNull(bottom, "stone_slab half=bottom variant=stone");
        assertBoxes(bottom, box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0));

        BlockData.State top = data.state("minecraft:stone_slab",
                props("half", "top", "variant", "stone"));
        assertNotNull(top, "stone_slab half=top variant=stone");
        assertBoxes(top, box(0.0, 0.5, 0.0, 1.0, 1.0, 1.0));
    }

    @Test
    void stairsEastBottomStraightHasBasePlusQuarter() throws IOException {
        BlockData data = dump();

        // BlockStairs.java:89-95 setBaseCollisionBounds: HALF=bottom -> base (0,0,0,1,0.5,1).
        // BlockStairs.java:225-300 func_176306_h: FACING=east -> f2=0.5, f5=1.0, so the
        // quarter box above the base is (0.5,0.5,0,1,1,1). BlockStairs.java:302-380
        // func_176304_i looks at neighbouring stairs only (all air in the dump, so no inner
        // boxes), and BlockStairs.java:385-389 chains base + quarter + inner.
        BlockData.State straight = data.state("minecraft:stone_stairs",
                props("facing", "east", "half", "bottom", "shape", "straight"));
        assertNotNull(straight, "stone_stairs facing=east half=bottom shape=straight");
        assertBoxes(straight,
                box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
                box(0.5, 0.5, 0.0, 1.0, 1.0, 1.0));

        // With no neighbouring stairs an inner corner is geometrically a straight stair for
        // collision (func_176304_i returns false), so the boxes must be identical.
        BlockData.State innerLeft = data.state("minecraft:stone_stairs",
                props("facing", "east", "half", "bottom", "shape", "inner_left"));
        assertNotNull(innerLeft, "stone_stairs facing=east half=bottom shape=inner_left");
        assertBoxes(innerLeft,
                box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
                box(0.5, 0.5, 0.0, 1.0, 1.0, 1.0));
    }

    @Test
    void trapdoorClosedBottomIsThreeSixteenthsSlab() throws IOException {
        BlockData data = dump();

        // BlockTrapDoor.java:70-72 getCollisionBoundingBox primes via
        // setBlockBoundsBasedOnState (lines 76-108): CLOSED + HALF=bottom -> the 3/16-thick
        // slab (line 95); OPEN + FACING=north -> the plate on the +Z face (line 99).
        BlockData.State closed = data.state("minecraft:trapdoor",
                props("facing", "north", "half", "bottom", "open", "false"));
        assertNotNull(closed, "trapdoor facing=north half=bottom open=false");
        assertBoxes(closed, box(0.0, 0.0, 0.0, 1.0, 0.1875, 1.0));

        BlockData.State open = data.state("minecraft:trapdoor",
                props("facing", "north", "half", "bottom", "open", "true"));
        assertNotNull(open, "trapdoor facing=north half=bottom open=true");
        assertBoxes(open, box(0.0, 0.0, 0.8125, 1.0, 1.0, 1.0));
    }

    @Test
    void loneFenceIsThePost() throws IOException {
        BlockData data = dump();

        // BlockFence.java:57-95 addCollisionBoxesToList: with no connections the first
        // branch (lines 72-75) is skipped and the second branch (lines 84-86) still runs
        // (!bl && !bl2), adding the default post box from lines 62-65 (f=0.375, g=0.625,
        // h=0.375, i=0.625) with maxY 1.5.
        BlockData.State lone = data.state("minecraft:fence",
                props("east", "false", "north", "false", "south", "false", "west", "false"));
        assertNotNull(lone, "fence with no connections");
        assertBoxes(lone, box(0.375, 0.0, 0.375, 0.625, 1.5, 0.625));
    }

    @Test
    void ladderHasThinPlateAndClimbs() throws IOException {
        BlockData data = dump();

        // BlockLadder.java:33-35 getCollisionBoundingBox primes via
        // setBlockBoundsBasedOnState (lines 39-59): FACING=north -> the plate on the +Z
        // face (0,0,0.875,1,1,1) (line 45), FACING=east -> (0,0,0,0.125,1,1) (line 57).
        // NOTE: task 1.0's description assumed ladders have no collision box; mc-src shows
        // they do, so this test follows the source (see docs/JOURNAL.md). Climbing itself
        // comes from BlockLadder.java:137 isLadder.
        BlockData.State north = data.state("minecraft:ladder", props("facing", "north"));
        assertNotNull(north, "ladder facing=north");
        assertBoxes(north, box(0.0, 0.0, 0.875, 1.0, 1.0, 1.0));
        assertTrue(north.climbable, "ladder must be climbable (BlockLadder.java:137)");

        BlockData.State east = data.state("minecraft:ladder", props("facing", "east"));
        assertNotNull(east, "ladder facing=east");
        assertBoxes(east, box(0.0, 0.0, 0.0, 0.125, 1.0, 1.0));
    }

    @Test
    void vineHasNoBoxesButClimbs() throws IOException {
        BlockData data = dump();

        // BlockVine.java:131-133 getCollisionBoundingBox returns null (vines never block
        // movement); BlockVine.java:366 isLadder returns true.
        BlockData.State bare = data.state("minecraft:vine",
                props("east", "false", "north", "false", "south", "false", "up", "false",
                        "west", "false"));
        assertNotNull(bare, "vine with no connections");
        assertBoxes(bare);
        assertTrue(bare.climbable, "vine must be climbable (BlockVine.java:366)");
    }

    @Test
    void iceSlipperinessIsPointNinetyEight() throws IOException {
        BlockData data = dump();

        // BlockIce.java:31 slipperiness = 0.98f; the box is the default full cube
        // (Block.java:620).
        BlockData.State ice = data.state("minecraft:ice", props());
        assertNotNull(ice, "ice (no properties)");
        assertEquals(0.98f, ice.slipperiness, "BlockIce.java:31");
        assertBoxes(ice, box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
    }

    /** Lazily loads the dump once; the LF guard runs before parsing. */
    private static BlockData dump() throws IOException {
        if (dump == null) {
            File file = dumpFile();
            assertLfOnly(file);
            dump = BlockData.load(file);
        }
        return dump;
    }

    /**
     * The dump must be LF-only on disk: it is the loader's byte-for-byte output (compared
     * by SHA-256 in task 1.0), and {@code readAllBytes} would silently tolerate CRLF.
     */
    private static void assertLfOnly(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0x0D) {
                fail("block dumps must be LF-only (loader output is LF-only): " + file
                        + " has a 0x0D byte at offset " + i);
            }
        }
    }

    /**
     * Repo root: the first ancestor of the test working dir with the repo marker
     * ({@link ScenarioLocator#ROOT_MARKER}, the root build file {@code settings.gradle}),
     * same convention as {@code FlatSimGoldenTest.golden}.
     */
    private static File dumpFile() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return new File(f, "data/1.8.9/blocks.json");
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static void assertBoxes(BlockData.State state, double[]... expected) {
        String where = "state " + state.props;
        if (expected.length != state.boxes.size()) {
            fail(where + ": expected " + render(expected) + " but the dump has "
                    + state.boxes.size() + " box(es): " + state.boxes);
        }
        for (int i = 0; i < expected.length; i++) {
            BlockData.Box actual = state.boxes.get(i);
            double[] e = expected[i];
            if (Double.compare(e[0], actual.minX) != 0 || Double.compare(e[1], actual.minY) != 0
                    || Double.compare(e[2], actual.minZ) != 0
                    || Double.compare(e[3], actual.maxX) != 0
                    || Double.compare(e[4], actual.maxY) != 0
                    || Double.compare(e[5], actual.maxZ) != 0) {
                fail(where + ": box " + i + " expected " + render(e)
                        + " but the dump has " + actual);
            }
        }
    }

    private static String render(double[] b) {
        return "[" + b[0] + ", " + b[1] + ", " + b[2] + ", " + b[3] + ", " + b[4] + ", "
                + b[5] + "]";
    }

    private static String render(double[][] boxes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < boxes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(render(boxes[i]));
        }
        return sb.append(']').toString();
    }

    private static double[] box(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ) {
        return new double[] { minX, minY, minZ, maxX, maxY, maxZ };
    }

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
