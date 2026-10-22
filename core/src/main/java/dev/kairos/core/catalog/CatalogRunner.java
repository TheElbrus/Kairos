package dev.kairos.core.catalog;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic per-entry catalog report for task 2.1. It validates every entry before
 * printing, writes one LF-terminated line per entry sorted by id, and returns nonzero on
 * an invalid, missing or unsupported catalog.
 */
public final class CatalogRunner {

    public static final String RELATIVE_DIR = "data/catalog";
    public static final String ROOT_MARKER = "settings.gradle";
    public static final int MAX_UP = 6;

    private CatalogRunner() {
    }

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, new File(System.getProperty("user.dir")), out, err);
    }

    static int run(String[] args, File startDir, PrintStream out, PrintStream err) {
        if (args == null || args.length > 1) {
            err.println("usage: CatalogRunner [catalogDir]");
            return 2;
        }
        File catalogDir;
        try {
            catalogDir = args.length == 1
                    ? new File(args[0])
                    : locate(startDir);
        } catch (IOException e) {
            err.println("catalog error: " + e.getMessage());
            return 1;
        }

        List<CatalogEntry> entries;
        try {
            entries = CatalogReader.readDirectory(catalogDir);
        } catch (IOException e) {
            err.println("catalog error: " + e.getMessage());
            return 1;
        }

        StringBuilder text = new StringBuilder(entries.size() * 128);
        for (CatalogEntry entry : entries) {
            appendLine(text, entry);
        }
        out.print(text.toString());
        out.flush();
        return 0;
    }

    public static File locate(File startDir) throws IOException {
        List<String> searched = new ArrayList<String>();
        File start = canonicalOrAbsolute(startDir == null ? new File(".") : startDir);

        for (File dir = start; dir != null; dir = dir.getParentFile()) {
            if (new File(dir, ROOT_MARKER).isFile()) {
                File candidate = new File(dir, RELATIVE_DIR);
                addUnique(searched, candidate);
                if (candidate.isDirectory()) {
                    return candidate;
                }
                break;
            }
        }

        int up = 0;
        for (File dir = start; dir != null && up < MAX_UP; dir = dir.getParentFile(), up++) {
            File candidate = new File(dir, RELATIVE_DIR);
            addUnique(searched, candidate);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        throw new IOException("no " + RELATIVE_DIR + " directory found; searched: " + searched);
    }

    private static void appendLine(StringBuilder out, CatalogEntry entry) {
        out.append("id=").append(entry.id)
                .append(" expect=").append(entry.expect.jsonName())
                .append(" source=").append(entry.labelSource.jsonName())
                .append(" start=").append(entry.startX).append(',').append(entry.startY)
                .append(',').append(entry.startZ)
                .append(" goal=").append(entry.goalX).append(',').append(entry.goalY)
                .append(',').append(entry.goalZ)
                .append(" runup=");
        for (int i = 0; i < entry.runup.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(entry.runup.get(i).intValue());
        }
        out.append(" tags=");
        for (int i = 0; i < entry.tags.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(entry.tags.get(i));
        }
        out.append('\n');
    }

    private static void addUnique(List<String> searched, File candidate) {
        String path = candidate.getPath();
        if (!searched.contains(path)) {
            searched.add(path);
        }
    }

    private static File canonicalOrAbsolute(File dir) {
        try {
            return dir.getCanonicalFile();
        } catch (IOException e) {
            return dir.getAbsoluteFile();
        }
    }
}
