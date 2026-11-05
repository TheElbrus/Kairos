package dev.kairos.core.replay;

import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;
import dev.kairos.core.trace.TraceLine;
import dev.kairos.core.trace.TraceWriter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.9 verifier guard: the L4 verifier must accept a well-formed trace and reject
 * every encoding/shape/input/state mutation before the 200 in-game traces are trusted.
 *
 * <p>The traces built here exist only in memory. They are never written to
 * {@code data/} and are not evidence; real evidence comes from the dev client.
 */
class SampleReplayVerifierTest {

    @Test
    void acceptsAWellFormedTraceAndComputesItsTarget() throws IOException {
        Scenario scenario = scenario(0);
        byte[] trace = syntheticTrace(scenario);
        SampleReplayVerifier.Replay replay = SampleReplayVerifier.verify(trace, scenario);
        assertTrue(replay.landed, "the corpus plan must land in the simulated replay");
        assertEquals(scenario.name, replay.name);
        assertEquals(0, replay.index);
        assertEquals(scenario.totalInputTicks() + 1, replay.lineCount);
        assertEquals(SampleCorpusGenerator.target(
                        SampleCorpusGenerator.gap(0), SampleCorpusGenerator.heightDelta(0),
                        scenario.startX).surfaceY,
                SampleReplayVerifier.targetOf(scenario).surfaceY, 0.0,
                "target surface must come from the scenario's goal block");
    }

    @Test
    void rejectsCrBomMissingLfAndWrongLineCount() throws IOException {
        Scenario scenario = scenario(0);
        byte[] trace = syntheticTrace(scenario);

        byte[] cr = trace.clone();
        cr[cr.length / 2] = (byte) 0x0D;
        assertRejected(trace, scenario, cr, "LF-only");

        byte[] bom = new byte[trace.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(trace, 0, bom, 3, trace.length);
        assertRejected(trace, scenario, bom, "BOM");

        byte[] noLf = new byte[trace.length - 1];
        System.arraycopy(trace, 0, noLf, 0, noLf.length);
        assertRejected(trace, scenario, noLf, "end with LF");

        String text = new String(trace, StandardCharsets.UTF_8);
        String shortTrace = text.substring(0, text.lastIndexOf('\n', text.length() - 2) + 1);
        assertRejected(trace, scenario, shortTrace.getBytes(StandardCharsets.UTF_8),
                "lines; expected");
    }

    @Test
    void rejectsAWrongScenarioLabelAndAWrongInput() throws IOException {
        Scenario scenario = scenario(0);
        byte[] trace = syntheticTrace(scenario);

        String text = new String(trace, StandardCharsets.UTF_8);
        assertRejected(trace, scenario, text.replace("\"scenario\":\"sample_0000\"",
                "\"scenario\":\"sample_0001\"").getBytes(StandardCharsets.UTF_8),
                "scenario label");

        String[] lines = text.split("\n", -1);
        // Tick 0 is line 1. Flip its first jump key; the corpus start never jumps.
        assertTrue(lines[1].contains("\"jump\":0"), "tick 0 must have jump:0");
        lines[1] = lines[1].replace("\"jump\":0", "\"jump\":1");
        assertRejected(trace, scenario, String.join("\n", lines)
                .getBytes(StandardCharsets.UTF_8), "in.jump");

        String[] stateLines = text.split("\n", -1);
        // The landing tick is onGround; find it from the end so this does not depend on
        // when this particular plan jumps.
        int lineIndex = -1;
        for (int i = stateLines.length - 2; i >= 1; i--) {
            if (stateLines[i].contains("\"onGround\":true")) {
                lineIndex = i;
                break;
            }
        }
        assertTrue(lineIndex > 0, "a recorded tick must be onGround");
        int tick = lineIndex - 1;
        stateLines[lineIndex] = stateLines[lineIndex].replace(
                "\"onGround\":true", "\"onGround\":false");
        assertRejected(trace, scenario, String.join("\n", stateLines)
                .getBytes(StandardCharsets.UTF_8), "tick " + tick + " onGround");
    }

    @Test
    void rejectsWrongFormatVersionGameVersionAndUsingState() throws IOException {
        Scenario scenario = scenario(0);
        byte[] trace = syntheticTrace(scenario);

        assertRejected(trace, scenario, mutateLine(trace, 0, "\"v\":1", "\"v\":2"),
                "anchor: v must be 1");
        assertRejected(trace, scenario, mutateLine(trace, 1, "\"v\":1", "\"v\":2"),
                "tick 0: v must be 1");
        assertRejected(trace, scenario, mutateLine(trace, 0, "\"mc\":\"1.8.9\"",
                "\"mc\":\"1.8.8\""), "anchor: mc must be 1.8.9");
        assertRejected(trace, scenario, mutateLine(trace, 1, "\"mc\":\"1.8.9\"",
                "\"mc\":\"1.8.8\""), "tick 0: mc must be 1.8.9");
        assertRejected(trace, scenario, mutateLine(trace, 0, "\"using\":false",
                "\"using\":true"), "anchor: using must be false");
        assertRejected(trace, scenario, mutateLine(trace, 1, "\"using\":false",
                "\"using\":true"), "tick 0: using must be false");
    }

    // --- in-memory trace builder ------------------------------------------------------

    /**
     * Builds the trace the client would write for this corpus scenario, from the game's
     * teleport start and the scenario inputs. Test-only: never written to disk.
     */
    static byte[] syntheticTrace(Scenario scenario) {
        CollisionWorld world = CollisionWorld.of(
                SampleCorpusGenerator.blockData(), scenario);
        MovementSim.State state = SampleCorpusGenerator.start(scenario.startX);
        StringBuilder text = new StringBuilder();
        text.append(TraceWriter.toJsonLine(new TraceLine(
                "1.8.9", scenario.name, -1, TraceInput.zero(),
                state.x, state.y, state.z, state.vx, state.vy, state.vz,
                state.onGround, state.sprinting, false, state.colH, state.colV, 0)))
                .append('\n');
        for (int tick = 0; tick < scenario.totalInputTicks(); tick++) {
            ScenarioInput segment = scenario.inputAt(tick);
            TraceInput in = inputOf(segment, scenario);
            MovementSim.step(state, in, world);
            text.append(TraceWriter.toJsonLine(new TraceLine(
                    "1.8.9", scenario.name, tick, in,
                    state.x, state.y, state.z, state.vx, state.vy, state.vz,
                    state.onGround, state.sprinting, false, state.colH, state.colV,
                    tick + 1)))
                    .append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static TraceInput inputOf(ScenarioInput segment, Scenario scenario) {
        float mf = segment.moveForward();
        float ms = segment.moveStrafe();
        if (segment.sneak != 0) {
            mf = (float) ((double) mf * 0.3);
            ms = (float) ((double) ms * 0.3);
        }
        float yaw = segment.yaw == null ? scenario.yaw : segment.yaw;
        return new TraceInput(segment.w, segment.a, segment.s, segment.d,
                segment.jump, segment.sneak, segment.sprint, yaw, scenario.pitch, mf, ms);
    }

    static Scenario scenario(int index) throws IOException {
        File root = SampleReplayVerifier.repoRoot();
        List<String> lines = ReplayScenarios.lines(root);
        return ScenarioParser.parse(lines.get(index));
    }

    private static byte[] mutateLine(byte[] trace, int lineIndex, String from, String to) {
        String[] lines = new String(trace, StandardCharsets.UTF_8).split("\n", -1);
        assertTrue(lines[lineIndex].contains(from),
                "line " + lineIndex + " must contain " + from);
        lines[lineIndex] = lines[lineIndex].replace(from, to);
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRejected(byte[] original, Scenario scenario, byte[] mutated,
            String expectedMessage) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SampleReplayVerifier.verify(mutated, scenario),
                "the verifier must reject this mutation");
        assertTrue(error.getMessage().contains(expectedMessage),
                "expected \"" + expectedMessage + "\" in: " + error.getMessage());
        SampleReplayVerifier.verify(original, scenario);   // the baseline stays valid
    }
}
