package dev.kairos.core.replay;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.solver.JumpTarget;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.8 data guard: the committed 200-line corpus must be exactly the generator
 * output, keep the recipe's starts/names/one-tick-per-plan-tick shape, and every plan
 * must replay from the teleport start onto its target through the same raw-key inputs
 * the loader applies.
 */
class SampleCorpusDataTest {

    private static String generated;

    @BeforeAll
    static void generateOnce() {
        generated = SampleCorpusGenerator.generateAll();
    }

    @Test
    void committedFileEqualsTheGenerator() throws IOException {
        assertEquals(generated, ReplayScenarios.readText(workDir()),
                ReplayScenarios.RELATIVE_PATH + " must equal "
                        + "SampleCorpusGenerator.generateAll(); regenerate the corpus, "
                        + "never hand-edit it");
    }

    @Test
    void overrideRejectsALineNamedForTheWrongIndex(@TempDir Path temp) throws IOException {
        String[] lines = generated.substring(0, generated.length() - 1).split("\n", -1);
        lines[42] = lines[42].replace("\"name\":\"sample_0042\"", "\"name\":\"sample_9999\"");
        Path file = temp.resolve("replay_sample.jsonl");
        StringBuilder text = new StringBuilder(generated.length());
        for (String line : lines) {
            text.append(line).append('\n');
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));

        String key = ReplayScenarios.OVERRIDE_PROPERTY;
        String old = System.getProperty(key);
        System.setProperty(key, file.toAbsolutePath().toString());
        try {
            IOException error = assertThrows(IOException.class,
                    () -> ReplayScenarios.lines(workDir()));
            assertTrue(error.getMessage().contains("sample_0042"), error.getMessage());
            assertTrue(error.getMessage().contains("sample_9999"), error.getMessage());
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    void missingOverrideListsTheSearchedPath(@TempDir Path temp) throws IOException {
        String key = ReplayScenarios.OVERRIDE_PROPERTY;
        String old = System.getProperty(key);
        File missing = temp.resolve("missing.jsonl").toFile();
        System.setProperty(key, missing.getAbsolutePath());
        try {
            IOException error = assertThrows(IOException.class,
                    () -> ReplayScenarios.lines(temp.toFile()));
            assertTrue(error.getMessage().contains(missing.getAbsolutePath()),
                    "error must name the searched path, got: " + error.getMessage());
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    void corpusIsUtf8LfOnlyWithoutBomAndHasNoBlankLines() throws IOException {
        File file = new File(SampleCorpusGenerator.repoRoot(), ReplayScenarios.RELATIVE_PATH);
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF,
                "no UTF-8 BOM");
        for (int i = 0; i < bytes.length; i++) {
            assertFalse(bytes[i] == 0x0D, "LF-only, found CR at byte " + i);
        }
        assertEquals(0x0A, bytes[bytes.length - 1], "trailing LF");

        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        assertEquals(ReplayScenarios.EXPECTED_COUNT, lines.length);
        for (String line : lines) {
            assertFalse(line.isEmpty(), "no blank lines");
        }
    }

    @Test
    void namesStartsAndRecipeMatchEachIndex() throws IOException {
        List<String> lines = ReplayScenarios.lines(workDir());
        assertEquals(ReplayScenarios.EXPECTED_COUNT, lines.size());
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < lines.size(); i++) {
            Scenario scenario = ScenarioParser.parse(lines.get(i));
            String where = "line " + (i + 1);
            assertEquals(SampleCorpusGenerator.name(i), scenario.name, where);
            assertTrue(names.add(scenario.name), "duplicate name " + scenario.name);

            int gap = SampleCorpusGenerator.gap(i);
            int dy = SampleCorpusGenerator.heightDelta(i);
            assertEquals(SampleCorpusGenerator.startX(i), scenario.startX, 0.0,
                    where + " startX");
            assertEquals(SampleCorpusGenerator.START_Y, scenario.startY, 0.0,
                    where + " startY");
            assertEquals(SampleCorpusGenerator.START_Z, scenario.startZ, 0.0,
                    where + " startZ");
            assertEquals(SampleCorpusGenerator.startYaw(i), scenario.yaw, 0.0f,
                    where + " yaw");
            assertEquals(0.0f, scenario.pitch, 0.0f, where + " pitch");
            assertEquals(SampleCorpusGenerator.SETTLE_TICKS, scenario.settleTicks, where);

            assertEquals(2, scenario.blocks.size(), where + " block regions");
            assertRegion(scenario.blocks.get(0), -3, 64, -20, 3, 64, 0, where);
            assertRegion(scenario.blocks.get(1), -3, 64 + dy, gap + 1, 3, 64 + dy,
                    gap + 7, where);

            assertTrue(scenario.totalInputTicks() >= 1, where + " needs inputs");
            assertTrue(scenario.totalInputTicks() <= SampleCorpusGenerator.TICK_BUDGET,
                    where + " exceeds the solver's tick budget");
            for (ScenarioInput in : scenario.inputs) {
                assertEquals(1, in.ticks, where + " must have one segment per plan tick");
                assertNotNull(in.yaw, where + " segment yaw");
                assertTrue(Float.isFinite(in.yaw.floatValue()), where + " finite yaw");
            }
        }
        assertEquals(ReplayScenarios.EXPECTED_COUNT, names.size());
    }

    @Test
    void everyPlanReplaysOntoItsTarget() throws IOException {
        BlockData blocks = SampleCorpusGenerator.blockData();
        List<String> lines = ReplayScenarios.lines(workDir());
        for (int i = 0; i < lines.size(); i++) {
            Scenario scenario = ScenarioParser.parse(lines.get(i));
            CollisionWorld world = CollisionWorld.of(blocks, scenario);
            MovementSim.State state = SampleCorpusGenerator.start(scenario.startX);
            JumpTarget target = SampleCorpusGenerator.target(
                    SampleCorpusGenerator.gap(i), SampleCorpusGenerator.heightDelta(i),
                    scenario.startX);
            for (int tick = 0; tick < scenario.totalInputTicks(); tick++) {
                ScenarioInput segment = scenario.inputAt(tick);
                assertNotNull(segment, scenario.name + " tick " + tick + " input");
                assertNotNull(segment.yaw, scenario.name + " tick " + tick + " yaw");
                TraceInput in = new TraceInput(segment.w, segment.a, segment.s, segment.d,
                        segment.jump, segment.sneak, segment.sprint,
                        segment.yaw.floatValue(), scenario.pitch,
                        segment.moveForward(), segment.moveStrafe());
                MovementSim.step(state, in, world);
            }
            assertTrue(target.isReachedBy(state), scenario.name + " did not land on "
                    + target + "; final state=(" + state.x + "," + state.y + "," + state.z
                    + ")");
        }
    }

    private static void assertRegion(ScenarioBlock block, int fromX, int fromY, int fromZ,
            int toX, int toY, int toZ, String where) {
        assertEquals(fromX, block.fromX, where + " fromX");
        assertEquals(fromY, block.fromY, where + " fromY");
        assertEquals(fromZ, block.fromZ, where + " fromZ");
        assertEquals(toX, block.toX, where + " toX");
        assertEquals(toY, block.toY, where + " toY");
        assertEquals(toZ, block.toZ, where + " toZ");
        assertEquals("stone", block.id, where + " id");
        assertEquals("stone", block.props.get("variant"), where + " stone variant");
        assertEquals(1, block.props.size(), where + " props");
    }

    private static File workDir() {
        return new File(System.getProperty("user.dir"));
    }
}
