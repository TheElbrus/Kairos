package dev.kairos.core.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One jump-catalog entry (PLAN.md 5.3). Coordinates are block coordinates; {@link #runup}
 * lists distances behind the start block along the approach axis, with 0 at the start.
 */
public final class CatalogEntry {

    /** Families the Phase 2 runner and solver understand. */
    public static final List<String> FAMILIES = Collections.unmodifiableList(Arrays.asList(
            "gap", "height", "offset", "ice", "slab", "headhitter", "ladder"));

    public final String id;
    public final int startX;
    public final int startY;
    public final int startZ;
    public final List<Integer> runup;
    public final int goalX;
    public final int goalY;
    public final int goalZ;
    public final Expect expect;
    public final LabelSource labelSource;
    public final List<String> tags;
    public final String primaryTag;

    public CatalogEntry(String id, int startX, int startY, int startZ, List<Integer> runup,
            int goalX, int goalY, int goalZ, Expect expect, LabelSource labelSource,
            List<String> tags) {
        if (id == null || expect == null || labelSource == null || runup == null || tags == null) {
            throw new NullPointerException("catalog entry fields must not be null");
        }
        this.id = id;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.runup = Collections.unmodifiableList(new ArrayList<Integer>(runup));
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.expect = expect;
        this.labelSource = labelSource;
        this.tags = Collections.unmodifiableList(new ArrayList<String>(tags));
        this.primaryTag = primaryTag(this.tags);
    }

    public static boolean isFamily(String tag) {
        return FAMILIES.contains(tag);
    }

    private static String primaryTag(List<String> tags) {
        for (String tag : tags) {
            if (isFamily(tag)) {
                return tag;
            }
        }
        throw new IllegalArgumentException("entry has no supported family tag " + FAMILIES
                + ": " + tags);
    }

    /** The allowed {@code expect} values. */
    public enum Expect {
        FEASIBLE("feasible"),
        INFEASIBLE("infeasible"),
        UNKNOWN("unknown");

        private final String jsonName;

        Expect(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return this.jsonName;
        }

        public static Expect fromJson(String value) {
            for (Expect expect : values()) {
                if (expect.jsonName.equals(value)) {
                    return expect;
                }
            }
            throw new IllegalArgumentException("expect must be feasible, infeasible or unknown, got \""
                    + value + "\"");
        }
    }

    /** The allowed {@code label_source} values. */
    public enum LabelSource {
        CONSTRUCTION("construction"),
        ENVELOPE("envelope"),
        WIKI("wiki"),
        ORACLE("oracle");

        private final String jsonName;

        LabelSource(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return this.jsonName;
        }

        public static LabelSource fromJson(String value) {
            for (LabelSource source : values()) {
                if (source.jsonName.equals(value)) {
                    return source;
                }
            }
            throw new IllegalArgumentException("label_source must be construction, envelope, wiki"
                    + " or oracle, got \"" + value + "\"");
        }
    }
}
