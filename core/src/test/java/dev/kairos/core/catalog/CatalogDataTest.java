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
                assertEquals(FEASIBLE, entry.expect, entry.id + " headhitter");
                assertEquals(CONSTRUCTION, entry.labelSource, entry.id + " headhitter source");
                assertTrue(entry.tags.contains("ceiling2"), entry.id + " must keep ceiling2");
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

    @Test
    void finalCountsAndLadderGeometryAreCanonical() throws IOException {
        List<CatalogEntry> entries = CatalogReader.readDirectory(CatalogRunner.locate(workDir()));
        assertEquals(21, entries.size(), "final catalog size");

        int feasible = 0;
        int infeasible = 0;
        int unknown = 0;
        int jumps = 0;
        int ladders = 0;
        for (CatalogEntry entry : entries) {
            if (entry.expect == FEASIBLE) {
                feasible++;
                if (entry.tags.contains("ladder")) {
                    ladders++;
                    assertTrue(entry.runup.isEmpty(),
                            entry.id + ": a ladder climb must not declare a run-up");
                    assertEquals(entry.startX, entry.goalX, entry.id + " climb stays in one column");
                    assertEquals(entry.startZ + 1, entry.goalZ,
                            entry.id + " goal must be one block forward");
                    int rise = entry.goalY - entry.startY;
                    assertTrue(rise == 2 || rise == 4, entry.id + " ladder rise " + rise);
                } else {
                    jumps++;
                }
            } else if (entry.expect == INFEASIBLE) {
                infeasible++;
            } else {
                unknown++;
            }
        }
        assertEquals(16, feasible, "14 single jumps + 2 ladder climbs");
        assertEquals(14, jumps, "feasible single jumps");
        assertEquals(2, ladders, "feasible ladder climbs");
        assertEquals(4, infeasible, "infeasible entries");
        assertEquals(1, unknown, "unknown entries");

        CatalogEntry headhitter = entry(entries, "headhitter_gap1_ceiling2");
        assertEquals(FEASIBLE, headhitter.expect, "headhitter must be feasible");
        assertEquals(CONSTRUCTION, headhitter.labelSource,
                "headhitter is constructible, not an envelope row");
        assertTrue(headhitter.tags.contains("ceiling2"), "headhitter keeps ceiling2");
        assertEquals(0, headhitter.startX);
        assertEquals(64, headhitter.startY);
        assertEquals(0, headhitter.startZ);
        assertEquals(0, headhitter.goalX);
        assertEquals(64, headhitter.goalY);
        assertEquals(2, headhitter.goalZ);

        CatalogEntry climb2 = entry(entries, "ladder_climb2");
        assertEquals(66, climb2.goalY, "ladder_climb2 goal");
        assertEquals(1, climb2.goalZ, "ladder_climb2 goal");
        CatalogEntry climb4 = entry(entries, "ladder_climb4");
        assertEquals(68, climb4.goalY, "ladder_climb4 goal");
        assertEquals(1, climb4.goalZ, "ladder_climb4 goal");
    }

    private static CatalogEntry entry(List<CatalogEntry> entries, String id) {
        for (CatalogEntry entry : entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        throw new AssertionError("catalog has no entry " + id);
    }

    private static File workDir() {
        return new File(System.getProperty("user.dir"));
    }
}
