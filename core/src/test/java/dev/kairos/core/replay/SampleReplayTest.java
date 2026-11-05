package dev.kairos.core.replay;

import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.9 L4 replay gate: the 200 manual in-game traces must match
 * {@link dev.kairos.core.sim.MovementSim} bit-exactly, land on their recipe target, and
 * hash exactly as recorded in the derived result file.
 *
 * <p>The traces and result are committed evidence. A missing directory or file is a
 * failure, never a skip; this test never manufactures a trace.
 */
class SampleReplayTest {

    @Test
    void allCommittedTracesMatchSimAndResult() throws IOException {
        File root = SampleReplayVerifier.repoRoot();
        File traceDir = new File(root, SampleReplayVerifier.TRACE_DIR);
        assertTrue(traceDir.isDirectory(),
                "missing committed trace directory " + SampleReplayVerifier.TRACE_DIR
                        + "; the L4 replay evidence must not be skipped");

        List<File> files = new ArrayList<File>();
        File[] listed = traceDir.listFiles();
        assertTrue(listed != null, "could not list " + traceDir);
        for (File file : listed) {
            if (file.isFile()) {
                files.add(file);
            }
        }
        assertEquals(ReplayScenarios.EXPECTED_COUNT, files.size(),
                "the trace directory must contain exactly "
                        + ReplayScenarios.EXPECTED_COUNT + " files");

        List<String> lines = ReplayScenarios.lines(root);
        List<Scenario> scenarios = new ArrayList<Scenario>(lines.size());
        List<byte[]> traces = new ArrayList<byte[]>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Scenario scenario = ScenarioParser.parse(lines.get(i));
            scenarios.add(scenario);
            File trace = new File(traceDir, SampleCorpusGenerator.name(i) + ".jsonl");
            assertTrue(trace.isFile(), "missing trace for " + SampleCorpusGenerator.name(i));
            traces.add(Files.readAllBytes(trace.toPath()));
        }

        for (int i = 0; i < scenarios.size(); i++) {
            SampleReplayVerifier.Replay replay =
                    SampleReplayVerifier.verify(traces.get(i), scenarios.get(i));
            assertTrue(replay.landed, replay.name + " must land on its target");
        }

        File result = new File(root, SampleReplayVerifier.RESULT_PATH);
        assertTrue(result.isFile(), "all traces exist but the derived result is missing: "
                + SampleReplayVerifier.RESULT_PATH);
        File corpus = new File(root, SampleReplayVerifier.CORPUS_PATH);
        String expected = SampleReplayVerifier.resultJson(
                Files.readAllBytes(corpus.toPath()), scenarios, traces);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(result.toPath()),
                "result file must equal the derived result, including every trace SHA-256");
    }
}
