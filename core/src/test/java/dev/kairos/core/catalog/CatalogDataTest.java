package dev.kairos.core.catalog;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.kairos.core.catalog.CatalogEntry.Expect.FEASIBLE;
import static dev.kairos.core.catalog.CatalogEntry.Expect.INFEASIBLE;
import static dev.kairos.core.catalog.CatalogEntry.Expect.UNKNOWN;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.CONSTRUCTION;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.ENVELOPE;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.ORACLE;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.WIKI;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the committed catalog (task 2.1): every file must be exactly what
 * {@link CatalogGenerator} emits, every family/source/expect value must be covered, and
 * envelope labels must stay inside the documented envelope.
 */
class CatalogDataTest {

    @Test
    void committedFilesEqualTheGenerator() throws IOException {
        File dir = CatalogRunner.locate(workDir());
        Map<String, String> expected = CatalogGenerator.generateFiles();
        File[] files = dir.listFiles();
        assertTrue(files != null, "catalog directory must be listable");
        assertEquals(expected.size(), files.length, "catalog file count");
        for (File file : files) {
            String name = file.getName();
            assertTrue(name.endsWith(".json"), "unexpected catalog entry " + name);
            String id = name.substring(0, name.length() - ".json".length());
            String body = expected.get(id);
            assertTrue(body != null, "unexpected catalog id " + id);
            assertEquals(body + "\n", CatalogReader.readText(file),
                    name + " must equal CatalogGenerator; regenerate, never hand-edit");
        }
    }

    @Test
    void everyFamilySourceAndExpectationIsCovered() throws IOException {
        List<CatalogEntry> entries = CatalogReader.readDirectory(CatalogRunner.locate(workDir()));
        assertEquals(CatalogGenerator.COUNT, entries.size());
        assertEquals(CatalogGenerator.generateFiles().size(), entries.size());

        Set<String> families = new HashSet<String>();
        Set<CatalogEntry.LabelSource> sources = new HashSet<CatalogEntry.LabelSource>();
        Set<CatalogEntry.Expect> expectations = new HashSet<CatalogEntry.Expect>();
        String previous = null;
        for (CatalogEntry entry : entries) {
            if (previous != null) {
                assertTrue(previous.compareTo(entry.id) < 0, "entries must be sorted by id");
            }
            previous = entry.id;
            assertEquals(entry.tags.get(0), entry.primaryTag, entry.id + " primary tag");
            assertTrue(entry.tags.contains(entry.primaryTag), entry.id + " family tag");
            for (String tag : entry.tags) {
                if (CatalogEntry.isFamily(tag)) {
                    families.add(tag);
                }
            }
            sources.add(entry.labelSource);
            expectations.add(entry.expect);
        }
        assertEquals(new HashSet<String>(CatalogEntry.FAMILIES), families);
        assertEquals(new HashSet<CatalogEntry.LabelSource>(
                Arrays.asList(CONSTRUCTION, ENVELOPE, ORACLE, WIKI)), sources);
        assertEquals(new HashSet<CatalogEntry.Expect>(
                Arrays.asList(FEASIBLE, INFEASIBLE, UNKNOWN)), expectations);
    }

    @Test
    void envelopeLabelsStayInsideTheEnvelope() throws IOException {
        for (CatalogEntry entry : CatalogReader.readDirectory(CatalogRunner.locate(workDir()))) {
            int dx = Math.abs(entry.goalX - entry.startX);
            int dy = entry.goalY - entry.startY;
            int dz = Math.abs(entry.goalZ - entry.startZ);

            if (entry.tags.contains("ladder")) {
                assertEquals(FEASIBLE, entry.expect, entry.id + " ladder");
                assertTrue(dy >= 0 && dy <= 4, entry.id + " ladder rise " + dy);
                continue;
            }
            if (entry.tags.contains("headhitter")) {
                assertEquals(INFEASIBLE, entry.expect, entry.id + " headhitter");
                continue;
            }
            if (entry.expect == FEASIBLE) {
                assertTrue(dy <= 1, entry.id + " feasible rise " + dy);
                assertTrue(dx <= 5 && dz <= 5, entry.id + " feasible horizontal " + dx + "," + dz);
            }
            if (entry.expect == INFEASIBLE) {
                assertTrue(dy >= 2 || dx >= 7 || dz >= 7,
                        entry.id + " must be outside the envelope, got " + dx + "," + dy + ","
                                + dz);
            }
            if (entry.expect == UNKNOWN) {
                assertEquals(6, dz, entry.id + " unknown is the 5-block gap");
            }
        }
    }

    private static File workDir() {
        return new File(System.getProperty("user.dir"));
    }
}
