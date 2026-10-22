package dev.kairos.core.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locating and validating the fuzz corpus through {@link FuzzScenarios} (task 1.7.1),
 * from the {@code core} module's working directory (the same class the dev loader calls
 * with {@code Minecraft.mcDataDir}).
 */
class FuzzScenariosTest {

    @Test
    void locatesAndReadsTheCommittedCorpus() throws IOException {
        List<String> lines = FuzzScenarios.lines(workDir());
        assertEquals(FuzzScenarios.EXPECTED_COUNT, lines.size());
        assertTrue(lines.get(0).contains("\"name\":\"fuzz_0000\""), "first line");
        assertTrue(lines.get(FuzzScenarios.EXPECTED_COUNT - 1).contains("\"name\":\"fuzz_0999\""),
                "last line");
    }

    @Test
    void readTextIsUtf8LfOnlyWithoutBom() throws IOException {
        String text = FuzzScenarios.readText(workDir());
        assertNotEquals(0, text.length());
        assertTrue(text.charAt(0) != '\uFEFF', "no UTF-8 BOM");
        assertTrue(text.indexOf('\r') < 0, "LF-only, no CR");
        assertTrue(text.endsWith("\n"), "trailing LF");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        assertEquals(FuzzScenarios.EXPECTED_COUNT, lines.length);
        for (String line : lines) {
            assertTrue(line.length() > 0, "no blank lines");
        }
    }

    @Test
    void missingCorpusListsTheSearchedPath(@TempDir Path temp) throws IOException {
        String key = FuzzScenarios.OVERRIDE_PROPERTY;
        String old = System.getProperty(key);
        File missing = temp.resolve("missing.jsonl").toFile();
        System.setProperty(key, missing.getAbsolutePath());
        try {
            IOException error = assertThrows(IOException.class,
                    () -> FuzzScenarios.lines(temp.toFile()));
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

    private static File workDir() {
        return new File(System.getProperty("user.dir"));
    }
}
