package dev.kairos.core.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ScenarioLocator}'s search order: override first, then the repo-root marker
 * walk, then the legacy parent walk, and a not-found message that names every path tried.
 *
 * <p>Uses a unique scenario name and temp directories so it can never accidentally find
 * the real {@code data/scenarios/} that lives above this test.
 */
class ScenarioLocatorTest {

    private static final String NAME = "locator_test_scenario";

    @Test
    void overrideDirectoryWinsOverTheMarkerAndFallback(@TempDir Path root) throws IOException {
        // A repo-root marker exists (so the marker walk would also succeed).
        Files.write(root.resolve(ScenarioLocator.ROOT_MARKER), new byte[0]);
        writeScenario(root.resolve("data/scenarios"), "body-from-repo");

        Path override = root.resolve("elsewhere");
        Path overrideFile = writeScenario(override, "body-from-override");

        File found = ScenarioLocator.locate(NAME, root.toFile(), override.toString());

        assertEquals(overrideFile.toFile().getCanonicalFile(), found.getCanonicalFile());
    }

    @Test
    void markerWalkFindsTheRepoRootAboveTheStartDirectory(@TempDir Path root) throws IOException {
        Files.write(root.resolve(ScenarioLocator.ROOT_MARKER), new byte[0]);
        Path expected = writeScenario(root.resolve("data/scenarios"), "body");

        // Start several levels below the marker, like run/ or build/run/, with a trailing
        // "/." that must be normalised away.
        Path start = root.resolve("loader-1.8.9/run/.");
        Files.createDirectories(root.resolve("loader-1.8.9/run"));

        File found = ScenarioLocator.locate(NAME, start.toFile(), null);

        assertEquals(expected.toFile().getCanonicalFile(), found.getCanonicalFile());
    }

    @Test
    void legacyFallbackWorksWithoutAMarker(@TempDir Path root) throws IOException {
        // No settings.gradle.kts anywhere under the temp root.
        Path expected = writeScenario(root.resolve("data/scenarios"), "body");

        Path start = root.resolve("run");
        Files.createDirectories(start);

        File found = ScenarioLocator.locate(NAME, start.toFile(), null);

        assertEquals(expected.toFile().getCanonicalFile(), found.getCanonicalFile());
    }

    @Test
    void missingFileReportsEveryPathSearched(@TempDir Path root) throws IOException {
        Files.write(root.resolve(ScenarioLocator.ROOT_MARKER), new byte[0]);
        Path start = root.resolve("run");
        Files.createDirectories(start);

        IOException error = assertThrows(IOException.class,
                () -> ScenarioLocator.locate(NAME, start.toFile(), null));

        String message = error.getMessage();
        assertTrue(message.startsWith("no data/scenarios/" + NAME + ".json"), message);
        // Every candidate path ends with the same relative tail, so count the occurrences:
        // the marker-root path and the start path (and its parents) must all be listed.
        // File.getPath() uses the platform separator, so build the tail the same way.
        String tail = new File("data/scenarios/" + NAME + ".json").getPath();
        assertTrue(countOf(message, tail) >= 2,
                "expected both the repo-root and start candidates: " + message);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            count++;
            i += needle.length();
        }
        return count;
    }

    private static Path writeScenario(Path dir, String body) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(NAME + ".json");
        Files.write(file, body.getBytes(Charset.forName("UTF-8")));
        return file;
    }
}
