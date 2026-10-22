package dev.kairos.core.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRunnerTest {

    @Test
    void printsOneDeterministicLinePerEntrySortedById() throws IOException {
        File dir = CatalogRunner.locate(workDir());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = CatalogRunner.run(new String[] {dir.getPath()},
                new PrintStream(stdout, true, "UTF-8"), new PrintStream(stderr, true, "UTF-8"));

        assertEquals(0, exit);
        assertEquals("", stderr.toString("UTF-8"));
        String text = stdout.toString("UTF-8");
        assertFalse(text.indexOf('\r') >= 0, "runner output must be LF-only");
        assertTrue(text.endsWith("\n"), "runner output must end with LF");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        assertEquals(CatalogGenerator.COUNT, lines.length);
        assertEquals("id=gap1_flat_sprint expect=feasible source=wiki"
                + " start=0,64,0 goal=0,64,2 runup=0,1,2,3 tags=gap,flat,sprint", lines[0]);
        assertEquals("id=gap4_flat_momentum expect=feasible source=oracle"
                + " start=0,64,0 goal=0,64,5 runup=0,1,2,3"
                + " tags=gap,flat,momentum,sprint", lines[3]);
    }

    @Test
    void malformedOrUnsupportedCatalogsReturnOneWithoutPartialOutput(@TempDir Path temp)
            throws IOException {
        write(temp, "bad_entry", "{\"id\":\"bad_entry\"}\n");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = CatalogRunner.run(new String[] {temp.toString()},
                new PrintStream(stdout, true, "UTF-8"), new PrintStream(stderr, true, "UTF-8"));

        assertEquals(1, exit);
        assertEquals("", stdout.toString("UTF-8"));
        assertTrue(stderr.toString("UTF-8").contains("invalid catalog entry"),
                stderr.toString("UTF-8"));

        Files.delete(temp.resolve("bad_entry.json"));
        write(temp, "water_jump", "{\"id\":\"water_jump\","
                + "\"start\":{\"block\":[0,64,0],\"runup\":[0]},"
                + "\"goal\":{\"block\":[0,64,1]},\"expect\":\"feasible\","
                + "\"label_source\":\"construction\",\"tags\":[\"water\"]}\n");
        stdout.reset();
        stderr.reset();
        exit = CatalogRunner.run(new String[] {temp.toString()},
                new PrintStream(stdout, true, "UTF-8"), new PrintStream(stderr, true, "UTF-8"));

        assertEquals(1, exit);
        assertEquals("", stdout.toString("UTF-8"));
        assertTrue(stderr.toString("UTF-8").contains("supported family"),
                stderr.toString("UTF-8"));
    }

    @Test
    void badArgumentsAndMissingDirectoriesReturnNonzero(@TempDir Path temp) throws IOException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int usage = CatalogRunner.run(new String[] {"one", "two"},
                new PrintStream(stdout, true, "UTF-8"), new PrintStream(stderr, true, "UTF-8"));
        assertEquals(2, usage);
        assertEquals("", stdout.toString("UTF-8"));
        assertTrue(stderr.toString("UTF-8").contains("usage:"));

        stdout.reset();
        stderr.reset();
        int missing = CatalogRunner.run(new String[] {temp.resolve("missing").toString()},
                new PrintStream(stdout, true, "UTF-8"), new PrintStream(stderr, true, "UTF-8"));
        assertEquals(1, missing);
        assertEquals("", stdout.toString("UTF-8"));
        assertTrue(stderr.toString("UTF-8").contains("does not exist"));
    }

    @Test
    void locatesTheCatalogThroughTheRepoRootMarker(@TempDir Path root) throws IOException {
        Files.write(root.resolve(CatalogRunner.ROOT_MARKER), new byte[0]);
        Path expected = Files.createDirectories(root.resolve(CatalogRunner.RELATIVE_DIR));
        Path start = Files.createDirectories(root.resolve("loader/run"));

        File found = CatalogRunner.locate(start.toFile());

        assertEquals(expected.toFile().getCanonicalFile(), found.getCanonicalFile());
    }

    private static void write(Path dir, String id, String body) throws IOException {
        Files.write(dir.resolve(id + ".json"), body.getBytes(StandardCharsets.UTF_8));
    }

    private static File workDir() {
        return new File(System.getProperty("user.dir"));
    }
}
