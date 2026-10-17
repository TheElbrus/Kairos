package dev.kairos.core.scenario;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds {@code data/scenarios/<name>.json} without depending on Minecraft or a loader
 * (AGENTS.md rule 1: {@code core} never imports {@code net.minecraft.*}).
 *
 * <p>Lookup order (first hit wins):
 * <ol>
 *   <li>an explicit override directory, normally from the
 *       {@code -Dkairos.scenariosDir=<dir>} system property (must contain
 *       {@code <name>.json});</li>
 *   <li>walk up from the start directory to the first directory containing the repo-root
 *       marker {@link #ROOT_MARKER} ({@code settings.gradle.kts}) and use
 *       {@code <root>/data/scenarios/<name>.json};</li>
 *   <li>the legacy fallback: walk up to {@link #MAX_UP} parents and test
 *       {@code data/scenarios/<name>.json} at each.</li>
 * </ol>
 *
 * <p>When nothing is found the thrown {@link IOException} lists every path that was
 * tried, so a failure says exactly where the loader looked.
 */
public final class ScenarioLocator {

    /** System property that overrides the scenarios directory (checked first). */
    public static final String OVERRIDE_PROPERTY = "kairos.scenariosDir";

    /** The repo-root marker; the first ancestor that has it is treated as the repo root. */
    public static final String ROOT_MARKER = "settings.gradle.kts";

    /** How many parent directories the legacy fallback climbs. */
    public static final int MAX_UP = 6;

    private static final String RELATIVE_DIR = "data/scenarios";

    private ScenarioLocator() {
    }

    /** Resolves the override from {@link #OVERRIDE_PROPERTY} and locates {@code name}. */
    public static File locate(String name, File startDir) throws IOException {
        return locate(name, startDir, System.getProperty(OVERRIDE_PROPERTY));
    }

    /**
     * @param name        scenario name, without the {@code .json} extension
     * @param startDir    the directory to start from, typically {@code Minecraft.mcDataDir}
     * @param overrideDir an explicit scenarios directory, or {@code null}/blank for none
     * @return the existing scenario file
     * @throws IOException if no candidate exists; the message lists every path searched
     */
    public static File locate(String name, File startDir, String overrideDir) throws IOException {
        String relative = RELATIVE_DIR + "/" + name + ".json";
        List<String> searched = new ArrayList<String>();

        // (a) explicit override.
        if (overrideDir != null && !overrideDir.trim().isEmpty()) {
            File candidate = new File(overrideDir.trim(), name + ".json");
            searched.add(candidate.getPath());
            if (candidate.isFile()) {
                return candidate;
            }
        }

        // getCanonicalFile() collapses a trailing "/." and "." segments; if it fails (a
        // drive that is not ready, say) fall back to the absolute path without throwing.
        File start = canonicalOrAbsolute(startDir);

        // (b) repo-root marker walk.
        for (File dir = start; dir != null; dir = dir.getParentFile()) {
            if (new File(dir, ROOT_MARKER).isFile()) {
                File candidate = new File(dir, relative);
                addUnique(searched, candidate);
                if (candidate.isFile()) {
                    return candidate;
                }
                break;   // the first marker is the repo root; do not look for a second
            }
        }

        // (c) legacy fallback: the original parent walk.
        int up = 0;
        for (File dir = start; dir != null && up < MAX_UP; dir = dir.getParentFile(), up++) {
            File candidate = new File(dir, relative);
            addUnique(searched, candidate);
            if (candidate.isFile()) {
                return candidate;
            }
        }

        throw new IOException("no " + relative + " found; searched: " + searched);
    }

    private static void addUnique(List<String> searched, File candidate) {
        String path = candidate.getPath();
        if (!searched.contains(path)) {
            searched.add(path);
        }
    }

    private static File canonicalOrAbsolute(File dir) {
        if (dir == null) {
            return null;
        }
        try {
            return dir.getCanonicalFile();
        } catch (IOException e) {
            return dir.getAbsoluteFile();
        }
    }
}
