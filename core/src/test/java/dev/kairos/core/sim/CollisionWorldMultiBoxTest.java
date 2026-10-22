package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Task 1.4.1 regression: non-connector states may dump more than the two boxes a
 * connector shape can produce. Hoppers and cauldrons emit five collision boxes
 * (BlockHopper.addCollisionBoxesToList, BlockCauldron.addCollisionBoxesToList), which
 * used to overflow the fixed two-box scratch in {@link CollisionWorld#of} with an
 * {@code ArrayIndexOutOfBoundsException} before the sim ever ran.
 *
 * <p>The expected boxes are the dump's own boxes (never hand-typed), in dump order and
 * offset by the cell origin, read back through the same box query an entity sweep uses.
 */
class CollisionWorldMultiBoxTest {

    @Test
    void hopperKeepsAllFiveBoxes() throws IOException {
        assertAllDumpBoxes("hopper", props("enabled", "false", "facing", "down"));
    }

    @Test
    void cauldronKeepsAllFiveBoxes() throws IOException {
        assertAllDumpBoxes("cauldron", props("level", "0"));
    }

    private static void assertAllDumpBoxes(String id, Map<String, String> props)
            throws IOException {
        BlockData dump = BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json"));
        BlockData.State state = dump.state("minecraft:" + id, props);
        assertNotNull(state, "dump state of " + id + " " + props);
        assertEquals(5, state.boxes.size(), id + " dump state must have five boxes");

        String json = "{\"name\": \"multi_box_l0\","
                + " \"start\": {\"pos\": [0.5, 1.0, 0.5]},"
                + " \"blocks\": [{\"from\": [0, 0, 0], \"to\": [0, 0, 0],"
                + " \"id\": \"" + id + "\", \"props\": " + propsJson(props) + "}],"
                + " \"inputs\": [{\"ticks\": 1}]}";
        CollisionWorld world = CollisionWorld.of(dump, ScenarioParser.parse(json));

        world.query(0.001, 0.001, 0.001, 0.999, 0.999, 0.999);
        assertEquals(state.boxes.size(), world.queryCount(), id + " box count");
        for (int i = 0; i < state.boxes.size(); i++) {
            BlockData.Box box = state.boxes.get(i);
            double[] actual = new double[6];
            world.boxBounds(world.queryBox(i), actual);
            assertArrayEquals(
                    new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ},
                    actual, 0.0, id + " box " + i + " in dump order");
        }
    }

    private static Map<String, String> props(String... keyValues) {
        Map<String, String> props = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put(keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    private static String propsJson(Map<String, String> props) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (!first) {
                json.append(", ");
            }
            json.append('"').append(entry.getKey()).append("\": \"")
                    .append(entry.getValue()).append('"');
            first = false;
        }
        return json.append('}').toString();
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
