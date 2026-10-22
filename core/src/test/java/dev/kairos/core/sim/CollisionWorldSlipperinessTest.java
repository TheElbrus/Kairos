package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 1.3 L0: the collision world must expose the raw {@code Block.slipperiness} of the
 * block under the feet (Block.java:321, dumped into {@link BlockData.State#slipperiness}).
 * Ice and packed ice read 0.98f, stone and air the 0.6f default, and positions outside
 * the dense grid are air. The lookup position is the game's
 * ({@code moveEntityWithHeading}, EntityLivingBase.java:1254-1264): the caller passes a
 * world position and the world floors it.
 */
class CollisionWorldSlipperinessTest {

    /** 0.91f, the air/off-ground factor in moveEntityWithHeading (:1254). */
    private static final float F91 = 0.91f;

    /**
     * JVM-computed {@code (double)(0.98f * 0.91f)}: what f4 is on ice. The float product
     * must stay the game's float expression, so the pin is the widened float, not
     * {@code 0.98 * 0.91} evaluated in double.
     */
    private static final double ICE_F4 = 0.891800045967102;

    @Test
    void iceAndPackedIceRead098() throws IOException {
        CollisionWorld world = world();

        assertEquals(0.98f, world.slipperinessAt(0.5, 65.0, 2.5), 0.0f,
                "ice (z=1..3) must read the dumped 0.98f");
        assertEquals(0.98f, world.slipperinessAt(0.5, 65.0, 5.5), 0.0f,
                "packed_ice (z=4..6) must read the dumped 0.98f");
    }

    @Test
    void stoneReads06() throws IOException {
        CollisionWorld world = world();
        assertEquals(0.6f, world.slipperinessAt(0.5, 65.0, 0.5), 0.0f,
                "stone must read the dumped 0.6f");
    }

    @Test
    void airAndUnassignedCellsRead06() throws IOException {
        CollisionWorld world = world();
        assertEquals(0.6f, world.slipperinessAt(0.5, 66.0, 8.5), 0.0f,
                "an air region must read air's dumped 0.6f");
        assertEquals(0.6f, world.slipperinessAt(0.5, 66.0, 0.5), 0.0f,
                "an unassigned cell inside the grid is air, 0.6f");
        assertEquals(0.6f, world.slipperinessAt(100.5, 65.0, 0.5), 0.0f,
                "outside the grid is air, 0.6f");
        assertEquals(0.6f, world.slipperinessAt(0.5, -40.0, 0.5), 0.0f,
                "below the grid is air, 0.6f");
    }

    @Test
    void floorsLikeMathHelper() throws IOException {
        CollisionWorld world = world();
        assertEquals(0.98f, world.slipperinessAt(0.5, 65.999, 2.5), 0.0f,
                "feet anywhere in [65, 66) look one block down: 64 -> ice");
        assertEquals(0.6f, world.slipperinessAt(0.5, 66.0, 2.5), 0.0f,
                "feet at exactly 66.0 look at 65 -> unassigned air");
        assertEquals(0.6f, world.slipperinessAt(-0.5, 65.0, -0.5), 0.0f,
                "floor(-0.5) is -1, still stone (x,z -2..2)");
        assertEquals(0.98f, world.slipperinessAt(-1.5, 65.0, 1.5), 0.0f,
                "floor(-1.5) is -2, still ice on x -2..2");
    }

    @Test
    void iceF4IsTheWidenedFloatProduct() {
        assertEquals(ICE_F4, (double) (0.98f * F91), 0.0,
                "expected f4 on ice is the JVM float product 0.98f*0.91f widened to double");
    }

    private static CollisionWorld world() throws IOException {
        String json = "{\"name\": \"slippery_l0\","
                + " \"start\": {\"pos\": [0.5, 65.0, 0.5]},"
                + " \"blocks\": ["
                + "  {\"from\": [-2, 64, -2], \"to\": [2, 64, 0], \"id\": \"stone\","
                + "   \"props\": {\"variant\": \"stone\"}},"
                + "  {\"from\": [-2, 64, 1], \"to\": [2, 64, 3], \"id\": \"ice\"},"
                + "  {\"from\": [-2, 64, 4], \"to\": [2, 64, 6], \"id\": \"packed_ice\"},"
                + "  {\"from\": [-2, 65, 7], \"to\": [2, 66, 9], \"id\": \"air\"}],"
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
