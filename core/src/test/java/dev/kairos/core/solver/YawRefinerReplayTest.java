package dev.kairos.core.solver;

import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Task 2.7 L4 replay gate: once the manual in-game run exists, its trace must match
 * {@link dev.kairos.core.sim.MovementSim} bit-exactly, land on the tight target, and hash
 * exactly as recorded in the generated result file.
 *
 * <p>The trace and result are external/manual artifacts. While they are absent this test
 * is skipped with the exact command to run; it never manufactures a trace.
 */
class YawRefinerReplayTest {

    @Test
    void committedTraceMatchesSimAndResult() throws IOException {
        File root = YawRefinerScenarioGenerator.repoRoot();
        File trace = new File(root, YawRefinerScenarioGenerator.TRACE_PATH);
        assumeTrue(trace.isFile(),
                "in-game replay pending: run /kairos scenario run "
                        + YawRefinerScenarioGenerator.SCENARIO_NAME
                        + " in the 1.8.9 dev client and copy the trace to "
                        + YawRefinerScenarioGenerator.TRACE_PATH);

        File result = new File(root, YawRefinerScenarioGenerator.RESULT_PATH);
        assertTrue(result.isFile(),
                "trace exists but its derived result is missing: "
                        + YawRefinerScenarioGenerator.RESULT_PATH);

        byte[] traceBytes = Files.readAllBytes(trace.toPath());
        File scenarioFile = new File(root, YawRefinerScenarioGenerator.SCENARIO_PATH);
        Scenario scenario = ScenarioParser.parse(new String(
                Files.readAllBytes(scenarioFile.toPath()), StandardCharsets.UTF_8));

        YawRefinerReplayVerifier.Replay replay =
                YawRefinerReplayVerifier.verify(traceBytes, scenario);
        assertTrue(YawRefinerScenarioGenerator.tightTarget().isReachedBy(replay.finalState),
                "committed trace must land on the tight target");

        String expected = YawRefinerReplayVerifier.resultJson(traceBytes, scenario);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(result.toPath()),
                "result file must equal the derived result, including the trace SHA-256");
    }
}
