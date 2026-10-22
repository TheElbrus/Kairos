package dev.kairos.core.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogReaderTest {

    @Test
    void readsThePlanExample(@TempDir Path temp) throws IOException {
        File file = write(temp, "gap4_flat_momentum", body("gap4_flat_momentum", "oracle"));

        CatalogEntry entry = CatalogReader.read(file);

        assertEquals("gap4_flat_momentum", entry.id);
        assertEquals(0, entry.startX);
        assertEquals(64, entry.startY);
        assertEquals(0, entry.startZ);
        assertEquals(4, entry.runup.size());
        assertEquals(3, entry.runup.get(3).intValue());
        assertEquals(0, entry.goalX);
        assertEquals(64, entry.goalY);
        assertEquals(5, entry.goalZ);
        assertEquals(CatalogEntry.Expect.FEASIBLE, entry.expect);
        assertEquals(CatalogEntry.LabelSource.ORACLE, entry.labelSource);
        assertEquals("gap", entry.primaryTag);
        assertEquals(3, entry.tags.size());
    }

    @Test
    void readsADirectorySortedByAndMatchingTheFileName(@TempDir Path temp) throws IOException {
        write(temp, "b_entry", body("b_entry", "construction"));
        write(temp, "a_entry", body("a_entry", "construction"));

        List<CatalogEntry> entries = CatalogReader.readDirectory(temp.toFile());

        assertEquals(2, entries.size());
        assertEquals("a_entry", entries.get(0).id);
        assertEquals("b_entry", entries.get(1).id);
    }

    @Test
    void rejectsFileAndDirectoryErrors(@TempDir Path temp) throws IOException {
        File wrongName = write(temp, "right_name", body("wrong_name", "construction"));
        IOException mismatch = assertThrows(IOException.class, () -> CatalogReader.read(wrongName));
        assertTrue(mismatch.getMessage().contains("must match"), mismatch.getMessage());

        Files.write(temp.resolve("README.txt"), new byte[0]);
        IOException extra = assertThrows(IOException.class,
                () -> CatalogReader.readDirectory(temp.toFile()));
        assertTrue(extra.getMessage().contains("unexpected entry"), extra.getMessage());

        Path empty = temp.resolve("empty");
        Files.createDirectories(empty);
        IOException emptyError = assertThrows(IOException.class,
                () -> CatalogReader.readDirectory(empty.toFile()));
        assertTrue(emptyError.getMessage().contains("empty"), emptyError.getMessage());

        IOException missing = assertThrows(IOException.class,
                () -> CatalogReader.readDirectory(temp.resolve("missing").toFile()));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
    }

    @Test
    void rejectsBomCrMissingLfAndInvalidUtf8(@TempDir Path temp) throws IOException {
        byte[] body = body("gap1_text", "construction").getBytes(StandardCharsets.UTF_8);

        byte[] bom = new byte[body.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bom, 3, body.length);
        File bomFile = writeBytes(temp, "gap1_text", bom);
        assertThrows(IOException.class, () -> CatalogReader.readText(bomFile));

        File crFile = writeBytes(temp, "gap1_text",
                body("gap1_text", "construction").replace("\n", "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> CatalogReader.readText(crFile));

        String withoutLf = body("gap1_text", "construction");
        File lfFile = writeBytes(temp, "gap1_text",
                withoutLf.substring(0, withoutLf.length() - 1).getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> CatalogReader.readText(lfFile));

        File utf8File = writeBytes(temp, "gap1_text", new byte[] {(byte) 0xC3, 0x28});
        assertThrows(IOException.class, () -> CatalogReader.readText(utf8File));
    }

    @Test
    void rejectsSchemaErrors(@TempDir Path temp) throws IOException {
        assertRejected(temp, "gap_schema", body("gap_schema", "construction")
                .replace("{\"id\"", "{\"extra\":1,\"id\""), "extra");
        assertRejected(temp, "gap_schema", body("gap_schema", "construction")
                .replace("\"goal\":{\"block\":[0,64,5]},", ""), "missing");
        assertRejected(temp, "gap_schema", body("gap_schema", "construction")
                .replace("\"runup\":[0,1,2,3]", "\"runup\":[0,1,2,3],\"extra\":1"), "extra");
        assertRejected(temp, "gap_schema", body("gap_schema", "construction")
                .replace("[0,64,0]", "[0,64]"), "3 integers");
        assertRejected(temp, "gap_schema", body("gap_schema", "construction")
                .replace("[0,64,0]", "[0,64,0.5]"), "integer");
    }

    @Test
    void rejectsBadRunups(@TempDir Path temp) throws IOException {
        assertRejected(temp, "gap_runup", withRunup("gap_runup", "[1,2,3]"), "start at 0");
        assertRejected(temp, "gap_runup", withRunup("gap_runup", "[0,2,1]"), "increasing");
        assertRejected(temp, "gap_runup", withRunup("gap_runup", "[0,1,2,3,4,5,6,7,8]"),
                "at most 8");
        assertRejected(temp, "gap_runup", withRunup("gap_runup", "[0,16]"), "0..15");
    }

    @Test
    void rejectsBadTags(@TempDir Path temp) throws IOException {
        assertRejected(temp, "gap_tags", withTags("gap_tags", "[]"), "must not be empty");
        assertRejected(temp, "gap_tags", withTags("gap_tags", "[\"flat\",\"flat\"]"),
                "duplicate");
        assertRejected(temp, "gap_tags", withTags("gap_tags", "[\"Gap\"]"), "must match");
        assertRejected(temp, "gap_tags", withTags("gap_tags", "[\"flat\",\"sprint\"]"),
                "at least one supported family");
    }

    @Test
    void rejectsUnsupportedLabelCombinations(@TempDir Path temp) throws IOException {
        assertRejected(temp, "gap_labels", body("gap_labels", "construction")
                .replace("\"expect\":\"feasible\"", "\"expect\":\"unknown\""), "must be feasible");
        assertRejected(temp, "gap_labels", body("gap_labels", "construction")
                .replace("\"expect\":\"feasible\"", "\"expect\":\"infeasible\""),
                "must be feasible");
        assertRejected(temp, "gap_labels", body("gap_labels", "construction")
                .replace("\"label_source\":\"construction\"", "\"label_source\":\"envelope\""),
                "cannot be feasible");
        assertRejected(temp, "gap_labels", body("gap_labels", "construction")
                .replace("\"label_source\":\"construction\"", "\"label_source\":\"wiki\"")
                .replace("\"expect\":\"feasible\"", "\"expect\":\"unknown\""),
                "must be feasible");
    }

    private static void assertRejected(Path dir, String id, String body, String expected)
            throws IOException {
        File file = write(dir, id, body);
        IOException error = assertThrows(IOException.class, () -> CatalogReader.read(file));
        assertTrue(error.getMessage().contains(expected),
                "expected \"" + expected + "\" in: " + error.getMessage());
    }

    private static String body(String id, String source) {
        return "{\"id\":\"" + id
                + "\",\"start\":{\"block\":[0,64,0],\"runup\":[0,1,2,3]},"
                + "\"goal\":{\"block\":[0,64,5]},\"expect\":\"feasible\","
                + "\"label_source\":\"" + source + "\","
                + "\"tags\":[\"gap\",\"flat\",\"momentum\"]}\n";
    }

    private static String withRunup(String id, String runup) {
        return body(id, "construction").replace("[0,1,2,3]", runup);
    }

    private static String withTags(String id, String tags) {
        return body(id, "construction").replace("[\"gap\",\"flat\",\"momentum\"]", tags);
    }

    private static File write(Path dir, String id, String text) throws IOException {
        return writeBytes(dir, id, text.getBytes(StandardCharsets.UTF_8));
    }

    private static File writeBytes(Path dir, String id, byte[] bytes) throws IOException {
        Path file = dir.resolve(id + ".json");
        Files.write(file, bytes);
        return file.toFile();
    }
}
