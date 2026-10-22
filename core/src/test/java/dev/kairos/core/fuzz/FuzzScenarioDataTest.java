package dev.kairos.core.fuzz;

import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;
import dev.kairos.core.scenario.ScenarioParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the committed 1000-scenario fuzz corpus (task 1.7.1): the file must be exactly what
 * {@link FuzzScenarioGenerator#generateAll()} produces, and every scenario must keep the
 * locked arena/input shape so task 1.7.2 records what was sealed here.
 */
class FuzzScenarioDataTest {

    private static final Set<String> PALETTE = new HashSet<String>(Arrays.asList(
            "stone", "stone_slab", "oak_stairs", "fence", "cobblestone_wall", "glass_pane",
            "iron_trapdoor", "anvil", "carpet", "snow_layer", "ice", "packed_ice", "air"));

    /** Tier C/D ids that must never appear (the palette check already excludes them). */
    private static final Set<String> FORBIDDEN = new HashSet<String>(Arrays.asList(
            "slime", "soul_sand", "web", "water", "flowing_water", "lava", "flowing_lava",
            "ladder", "vine", "waterlily", "piston", "sticky_piston", "chest",
            "trapped_chest", "ender_chest", "furnace", "hopper", "cauldron"));

    private static final Set<String> FACINGS =
            new HashSet<String>(Arrays.asList("north", "south", "east", "west"));

    @Test
    void committedFileEqualsTheGenerator() throws IOException {
        String expected = FuzzScenarioGenerator.generateAll();
        String actual = FuzzScenarios.readText(workDir());
        assertEquals(expected, actual,
                "data/fuzz/scenarios.jsonl must equal FuzzScenarioGenerator.generateAll(); "
                        + "regenerate the corpus, never hand-edit it");
    }

    @Test
    void namesAreSequentialUniqueAndTheShapeIsLocked() throws IOException {
        List<String> lines = FuzzScenarios.lines(workDir());
        assertEquals(FuzzScenarios.EXPECTED_COUNT, lines.size());
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < lines.size(); i++) {
            Scenario scenario = ScenarioParser.parse(lines.get(i));
            assertEquals("fuzz_" + pad4(i), scenario.name, "line " + (i + 1));
            assertTrue(names.add(scenario.name), "duplicate name " + scenario.name);
            assertEquals(FuzzScenarioGenerator.TICKS, scenario.totalInputTicks(),
                    scenario.name + " must record exactly " + FuzzScenarioGenerator.TICKS
                            + " ticks");
            assertEquals(10, scenario.settleTicks, scenario.name + " settleTicks");
            assertEquals(0.5, scenario.startX, 0.0, scenario.name + " startX");
            assertEquals(65.0, scenario.startY, 0.0, scenario.name + " startY");
            assertEquals(0.5, scenario.startZ, 0.0, scenario.name + " startZ");
            assertEquals(0.0f, scenario.pitch, 0.0f, scenario.name + " pitch");
            assertTrue(Float.isFinite(scenario.yaw), scenario.name + " yaw must be finite");
            assertTrue(scenario.yaw >= 0.0f && scenario.yaw < 360.0f,
                    scenario.name + " yaw must be in [0, 360), got " + scenario.yaw);
        }
        assertEquals(FuzzScenarios.EXPECTED_COUNT, names.size());
    }

    @Test
    void blocksUseOnlyTheAllowedPaletteAndAvoidTheStart() throws IOException {
        List<String> lines = FuzzScenarios.lines(workDir());
        for (int i = 0; i < lines.size(); i++) {
            Scenario scenario = ScenarioParser.parse(lines.get(i));
            for (ScenarioBlock block : scenario.blocks) {
                String where = scenario.name + " block " + block.id + " "
                        + block.fromX + "," + block.fromY + "," + block.fromZ;
                assertTrue(PALETTE.contains(block.id), where + " is not in the palette");
                assertFalse(FORBIDDEN.contains(block.id), where + " is a Tier C/D block");
                assertProps(block, where);
                if (!"air".equals(block.id)) {
                    assertFalse(overlapsStart(block),
                            where + " must not occupy the 5x5 start clearing");
                }
            }
        }
    }

    @Test
    void regionsAreDisjointAndCoverTheArenaExactlyOnce() throws IOException {
        List<String> lines = FuzzScenarios.lines(workDir());
        int expectedVolume = 49 * 49 * 5;   // x,z -24..24, y 64..68
        for (int i = 0; i < lines.size(); i++) {
            Scenario scenario = ScenarioParser.parse(lines.get(i));
            boolean[][][] seen = new boolean[5][49][49];
            int covered = 0;
            for (ScenarioBlock block : scenario.blocks) {
                for (int y = Math.min(block.fromY, block.toY);
                        y <= Math.max(block.fromY, block.toY); y++) {
                    for (int z = Math.min(block.fromZ, block.toZ);
                            z <= Math.max(block.fromZ, block.toZ); z++) {
                        for (int x = Math.min(block.fromX, block.toX);
                                x <= Math.max(block.fromX, block.toX); x++) {
                            String at = scenario.name + " at " + x + "," + y + "," + z;
                            if (x < -24 || x > 24 || z < -24 || z > 24 || y < 64 || y > 68) {
                                fail(at + " is outside the fuzz arena");
                            }
                            if (seen[y - 64][z + 24][x + 24]) {
                                fail(at + " is covered by more than one region");
                            }
                            seen[y - 64][z + 24][x + 24] = true;
                            covered++;
                        }
                    }
                }
            }
            assertEquals(expectedVolume, covered,
                    scenario.name + " regions must cover the arena exactly once");
        }
    }

    private static void assertProps(ScenarioBlock block, String where) {
        Map<String, String> props = block.props;
        if ("stone".equals(block.id)) {
            assertPropsExactly(props, where, singleton("variant", "stone"));
        } else if ("stone_slab".equals(block.id)) {
            Map<String, String> expected = new HashMap<String, String>();
            expected.put("half", "bottom");
            expected.put("variant", "stone");
            assertPropsExactly(props, where, expected);
        } else if ("oak_stairs".equals(block.id)) {
            assertPropsExactly(props, where, stairs());
        } else if ("fence".equals(block.id)) {
            assertPropsExactly(props, where, fourFalse());
        } else if ("cobblestone_wall".equals(block.id)) {
            Map<String, String> expected = fourFalse();
            expected.put("up", "false");
            expected.put("variant", "cobblestone");
            assertPropsExactly(props, where, expected);
        } else if ("glass_pane".equals(block.id)) {
            assertPropsExactly(props, where, fourFalse());
        } else if ("iron_trapdoor".equals(block.id)) {
            assertTrapdoor(props, where);
        } else if ("anvil".equals(block.id)) {
            assertAnvil(props, where);
        } else if ("carpet".equals(block.id)) {
            assertOneOf(props, where, "color", "red", "blue", "white");
        } else if ("snow_layer".equals(block.id)) {
            assertOneOf(props, where, "layers", "2", "4");
        } else {
            assertTrue(props.isEmpty(), where + " must have no props, got " + props);
        }
    }

    private static Map<String, String> stairs() {
        Map<String, String> expected = new HashMap<String, String>();
        expected.put("facing", "<any>");
        expected.put("half", "bottom");
        expected.put("shape", "straight");
        return expected;
    }

    private static Map<String, String> fourFalse() {
        Map<String, String> expected = new HashMap<String, String>();
        expected.put("east", "false");
        expected.put("north", "false");
        expected.put("south", "false");
        expected.put("west", "false");
        return expected;
    }

    private static Map<String, String> singleton(String key, String value) {
        Map<String, String> map = new HashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private static void assertTrapdoor(Map<String, String> props, String where) {
        Map<String, String> copy = new HashMap<String, String>(props);
        assertTrue(FACINGS.contains(copy.remove("facing")), where + " facing");
        assertEquals("bottom", copy.remove("half"), where + " half");
        assertEquals("false", copy.remove("open"), where + " open");
        assertTrue(copy.isEmpty(), where + " has unexpected props " + copy);
    }

    private static void assertAnvil(Map<String, String> props, String where) {
        Map<String, String> copy = new HashMap<String, String>(props);
        assertEquals("0", copy.remove("damage"), where + " damage");
        assertTrue(FACINGS.contains(copy.remove("facing")), where + " facing");
        assertTrue(copy.isEmpty(), where + " has unexpected props " + copy);
    }

    private static void assertOneOf(Map<String, String> props, String where, String key,
            String... allowed) {
        Map<String, String> copy = new HashMap<String, String>(props);
        assertTrue(Arrays.asList(allowed).contains(copy.remove(key)), where + " " + key);
        assertTrue(copy.isEmpty(), where + " has unexpected props " + copy);
    }

    /** Exact equality, except the {@code <any>} marker allows any known facing value. */
    private static void assertPropsExactly(Map<String, String> actual, String where,
            Map<String, String> expected) {
        if (!actual.keySet().equals(expected.keySet())) {
            fail(where + " props keys " + actual.keySet() + " != " + expected.keySet());
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String value = actual.get(entry.getKey());
            if ("<any>".equals(entry.getValue())) {
                assertTrue(FACINGS.contains(value), where + " " + entry.getKey() + "=" + value);
            } else {
                assertEquals(entry.getValue(), value, where + " " + entry.getKey());
            }
        }
    }

    private static boolean overlapsStart(ScenarioBlock block) {
        int x0 = Math.min(block.fromX, block.toX);
        int x1 = Math.max(block.fromX, block.toX);
        int y0 = Math.min(block.fromY, block.toY);
        int y1 = Math.max(block.fromY, block.toY);
        int z0 = Math.min(block.fromZ, block.toZ);
        int z1 = Math.max(block.fromZ, block.toZ);
        return x0 <= 2 && x1 >= -2 && y0 <= 68 && y1 >= 65 && z0 <= 2 && z1 >= -2;
    }

    private static String pad4(int value) {
        StringBuilder out = new StringBuilder(4);
        if (value < 10) {
            out.append('0');
        }
        if (value < 100) {
            out.append('0');
        }
        if (value < 1000) {
            out.append('0');
        }
        return out.append(value).toString();
    }

    private static File workDir() {
        return new File(System.getProperty("user.dir"));
    }
}
