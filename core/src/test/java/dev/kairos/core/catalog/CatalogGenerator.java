package dev.kairos.core.catalog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dev.kairos.core.catalog.CatalogEntry.Expect.FEASIBLE;
import static dev.kairos.core.catalog.CatalogEntry.Expect.INFEASIBLE;
import static dev.kairos.core.catalog.CatalogEntry.Expect.UNKNOWN;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.CONSTRUCTION;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.ENVELOPE;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.ORACLE;
import static dev.kairos.core.catalog.CatalogEntry.LabelSource.WIKI;

/**
 * Deterministic generator for the committed single-jump catalog (task 2.1). The corpus is
 * data, not generated at runtime: this class lives in the test sources, and
 * {@code CatalogDataTest} pins the committed bytes.
 *
 * <p>Labels: gaps 1-3, offset_side2 and the 1/4-block ice gaps are wiki reachability
 * (docs/physics-notes.md 2.10); gap 4 is the Gate 0.5 in-game oracle; height, slab,
 * offset_side1, ladder and the remaining feasible entries are constructible; envelope
 * entries are the 2-block rise, the headhitter, the 5-block unknown, and the gaps/offsets
 * outside the tested 4-block envelope. No solver is called here.
 */
public final class CatalogGenerator {

    public static final int COUNT = 21;

    private static final List<CatalogEntry> ENTRIES = Collections.unmodifiableList(
            Arrays.asList(
                    entry("gap1_flat_sprint", 0, 64, 0, runup(0, 1, 2, 3), 0, 64, 2,
                            FEASIBLE, WIKI, "gap", "flat", "sprint"),
                    entry("gap2_flat_sprint", 0, 64, 0, runup(0, 1, 2, 3), 0, 64, 3,
                            FEASIBLE, WIKI, "gap", "flat", "sprint"),
                    entry("gap3_flat_sprint", 0, 64, 0, runup(0, 1, 2, 3), 0, 64, 4,
                            FEASIBLE, WIKI, "gap", "flat", "sprint"),
                    entry("gap4_flat_momentum", 0, 64, 0, runup(0, 1, 2, 3), 0, 64, 5,
                            FEASIBLE, ORACLE, "gap", "flat", "momentum", "sprint"),
                    entry("gap5_flat_unknown", 0, 64, 0, runup(0, 1, 2, 3), 0, 64, 6,
                            UNKNOWN, ENVELOPE, "gap", "flat"),
                    entry("gap6_flat_infeasible", 0, 64, 0, runup(0, 1, 2, 3), 0, 64, 7,
                            INFEASIBLE, ENVELOPE, "gap", "flat"),
                    entry("height_down1_jump", 0, 65, 0, runup(0, 1, 2, 3), 0, 64, 2,
                            FEASIBLE, CONSTRUCTION, "height", "drop", "gap"),
                    entry("height_down3_jump", 0, 67, 0, runup(0, 1, 2, 3), 0, 64, 2,
                            FEASIBLE, CONSTRUCTION, "height", "drop", "gap"),
                    entry("height_up1_jump", 0, 64, 0, runup(0, 1, 2, 3), 0, 65, 1,
                            FEASIBLE, CONSTRUCTION, "height", "step", "jump"),
                    entry("height_up2_wall", 0, 64, 0, runup(0, 1, 2, 3), 0, 66, 1,
                            INFEASIBLE, ENVELOPE, "height", "wall"),
                    entry("headhitter_gap1_ceiling2", 0, 64, 0, runup(0, 1, 2, 3), 0, 64, 2,
                            INFEASIBLE, ENVELOPE, "headhitter", "gap", "flat", "ceiling2"),
                    entry("ice_gap1_sprint", 0, 64, 0, runup(0, 1, 2, 3, 4, 5), 0, 64, 2,
                            FEASIBLE, WIKI, "ice", "gap", "flat", "sprint"),
                    entry("ice_gap4_sprint", 0, 64, 0, runup(0, 1, 2, 3, 4, 5), 0, 64, 5,
                            FEASIBLE, WIKI, "ice", "gap", "flat", "sprint", "momentum"),
                    entry("ice_gap8_infeasible", 0, 64, 0, runup(0, 1, 2, 3, 4, 5), 0, 64, 9,
                            INFEASIBLE, ENVELOPE, "ice", "gap", "flat"),
                    entry("ladder_climb2", 0, 64, 0, runup(), 0, 66, 0,
                            FEASIBLE, CONSTRUCTION, "ladder", "climb"),
                    entry("ladder_climb4", 0, 64, 0, runup(), 0, 68, 0,
                            FEASIBLE, CONSTRUCTION, "ladder", "climb"),
                    entry("offset_side1_gap1", 0, 64, 0, runup(0, 1, 2, 3), 1, 64, 2,
                            FEASIBLE, CONSTRUCTION, "offset", "gap", "flat"),
                    entry("offset_side2_gap2", 0, 64, 0, runup(0, 1, 2, 3), 2, 64, 3,
                            FEASIBLE, WIKI, "offset", "gap", "flat", "sprint"),
                    entry("offset_side8_infeasible", 0, 64, 0, runup(0, 1, 2, 3), 8, 64, 1,
                            INFEASIBLE, ENVELOPE, "offset", "gap"),
                    entry("slab_down_half", 0, 65, 0, runup(0, 1, 2, 3), 0, 64, 2,
                            FEASIBLE, CONSTRUCTION, "slab", "drop", "gap"),
                    entry("slab_up_half", 0, 64, 0, runup(0, 1, 2, 3), 0, 65, 1,
                            FEASIBLE, CONSTRUCTION, "slab", "step")));

    private CatalogGenerator() {
    }

    public static Map<String, String> generateFiles() {
        Map<String, String> files = new TreeMap<String, String>();
        for (CatalogEntry entry : ENTRIES) {
            String previous = files.put(entry.id, toJson(entry));
            if (previous != null) {
                throw new IllegalStateException("duplicate catalog id " + entry.id);
            }
        }
        return Collections.unmodifiableMap(files);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: CatalogGenerator <catalogDir>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        Files.createDirectories(dir.toPath());
        Map<String, String> files = generateFiles();

        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.write(new File(dir, file.getKey() + ".json").toPath(),
                    (file.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File file : existing) {
                String name = file.getName();
                if (file.isFile() && name.endsWith(".json")
                        && !files.containsKey(name.substring(0, name.length() - 5))) {
                    Files.delete(file.toPath());
                }
            }
        }
        System.out.println("wrote " + files.size() + " files to " + dir);
    }

    private static CatalogEntry entry(String id, int startX, int startY, int startZ,
            List<Integer> runup, int goalX, int goalY, int goalZ,
            CatalogEntry.Expect expect, CatalogEntry.LabelSource source, String... tags) {
        return new CatalogEntry(id, startX, startY, startZ, runup, goalX, goalY, goalZ,
                expect, source, Arrays.asList(tags));
    }

    private static List<Integer> runup(int... values) {
        List<Integer> list = new ArrayList<Integer>(values.length);
        for (int value : values) {
            list.add(Integer.valueOf(value));
        }
        return list;
    }

    private static String toJson(CatalogEntry entry) {
        StringBuilder out = new StringBuilder(160);
        out.append("{\"id\":\"").append(entry.id)
                .append("\",\"start\":{\"block\":[").append(entry.startX).append(',')
                .append(entry.startY).append(',').append(entry.startZ).append("],\"runup\":[");
        for (int i = 0; i < entry.runup.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(entry.runup.get(i).intValue());
        }
        out.append("]},\"goal\":{\"block\":[").append(entry.goalX).append(',')
                .append(entry.goalY).append(',').append(entry.goalZ)
                .append("]},\"expect\":\"").append(entry.expect.jsonName())
                .append("\",\"label_source\":\"").append(entry.labelSource.jsonName())
                .append("\",\"tags\":[");
        for (int i = 0; i < entry.tags.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(entry.tags.get(i)).append('"');
        }
        out.append("]}");
        return out.toString();
    }
}
