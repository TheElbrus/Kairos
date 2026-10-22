package dev.kairos.core.catalog;

import dev.kairos.core.scenario.Json;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads strict {@code data/catalog/<id>.json} entries (PLAN.md 5.3): one object per file,
 * UTF-8 without a BOM, LF-only, and terminated by LF.
 */
public final class CatalogReader {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_RUNUP = 8;
    private static final int MAX_RUNUP_DISTANCE = 15;

    private static final Set<String> ENTRY_KEYS = keys(
            "id", "start", "goal", "expect", "label_source", "tags");
    private static final Set<String> START_KEYS = keys("block", "runup");
    private static final Set<String> GOAL_KEYS = keys("block");

    private CatalogReader() {
    }

    /** Reads every {@code .json} file in {@code dir}, sorted by id. */
    public static List<CatalogEntry> readDirectory(File dir) throws IOException {
        if (dir == null) {
            throw new NullPointerException("dir");
        }
        if (!dir.isDirectory()) {
            throw new IOException("catalog directory does not exist: " + dir);
        }
        File[] files = dir.listFiles();
        if (files == null) {
            throw new IOException("cannot list catalog directory: " + dir);
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        List<CatalogEntry> entries = new ArrayList<CatalogEntry>();
        Set<String> ids = new TreeSet<String>();
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json")) {
                throw new IOException("unexpected entry in catalog directory: " + file);
            }
            CatalogEntry entry = read(file);
            if (!ids.add(entry.id)) {
                throw new IOException("duplicate catalog id \"" + entry.id + "\" ("
                        + file + ")");
            }
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            throw new IOException("catalog directory is empty: " + dir);
        }
        Collections.sort(entries, new Comparator<CatalogEntry>() {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return a.id.compareTo(b.id);
            }
        });
        return Collections.unmodifiableList(entries);
    }

    /** Reads one entry and requires its id to match the file name. */
    public static CatalogEntry read(File file) throws IOException {
        if (file == null) {
            throw new NullPointerException("file");
        }
        if (!file.isFile()) {
            throw new IOException("catalog entry does not exist: " + file);
        }
        String name = file.getName();
        if (!name.endsWith(".json") || name.length() <= ".json".length()) {
            throw new IOException("catalog entry must be named <id>.json: " + file);
        }
        String fileId = name.substring(0, name.length() - ".json".length());
        String text = readText(file);
        CatalogEntry entry;
        try {
            entry = parse(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid catalog entry " + file + ": " + e.getMessage(), e);
        }
        if (!fileId.equals(entry.id)) {
            throw new IOException("catalog file " + file + " contains id \"" + entry.id
                    + "\" (file name and id must match)");
        }
        return entry;
    }

    /** The file text after the BOM, CR and trailing-LF checks. */
    public static String readText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            throw new IOException("catalog entry must not start with a UTF-8 BOM: " + file);
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0x0D) {
                throw new IOException("catalog entry must be LF-only, found CR at byte " + i
                        + ": " + file);
            }
        }
        String text;
        try {
            text = decode(bytes);
        } catch (CharacterCodingException e) {
            throw new IOException("catalog entry is not valid UTF-8: " + file, e);
        }
        if (!text.endsWith("\n")) {
            throw new IOException("catalog entry must end with LF: " + file);
        }
        return text;
    }

    private static CatalogEntry parse(String text) {
        Map<String, Object> root = object(Json.parse(text), "entry");
        requireKeys(root, ENTRY_KEYS, "entry");

        String id = string(root.get("id"), "id");
        if (id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("id must be at most " + MAX_ID_LENGTH
                    + " characters, got " + id.length());
        }
        if (!NAME.matcher(id).matches()) {
            throw new IllegalArgumentException("id must match [a-z0-9_]+, got \"" + id + "\"");
        }

        Map<String, Object> start = object(root.get("start"), "start");
        requireKeys(start, START_KEYS, "start");
        int[] startBlock = intTriple(start.get("block"), "start.block");
        List<Integer> runup = runup(start.get("runup"));

        Map<String, Object> goal = object(root.get("goal"), "goal");
        requireKeys(goal, GOAL_KEYS, "goal");
        int[] goalBlock = intTriple(goal.get("block"), "goal.block");

        CatalogEntry.Expect expect = CatalogEntry.Expect.fromJson(
                string(root.get("expect"), "expect"));
        CatalogEntry.LabelSource source = CatalogEntry.LabelSource.fromJson(
                string(root.get("label_source"), "label_source"));
        List<String> tags = tags(root.get("tags"));

        boolean hasFamily = false;
        for (String tag : tags) {
            hasFamily |= CatalogEntry.isFamily(tag);
        }
        if (!hasFamily) {
            throw new IllegalArgumentException("tags need at least one supported family "
                    + CatalogEntry.FAMILIES + ", got " + tags);
        }

        if (source == CatalogEntry.LabelSource.ENVELOPE
                && expect == CatalogEntry.Expect.FEASIBLE) {
            throw new IllegalArgumentException("envelope labels cannot be feasible");
        }
        if (source != CatalogEntry.LabelSource.ENVELOPE
                && expect != CatalogEntry.Expect.FEASIBLE) {
            throw new IllegalArgumentException(source.jsonName()
                    + " labels must be feasible, got " + expect.jsonName());
        }

        return new CatalogEntry(id, startBlock[0], startBlock[1], startBlock[2], runup,
                goalBlock[0], goalBlock[1], goalBlock[2], expect, source, tags);
    }

    private static List<Integer> runup(Object value) {
        List<?> list = array(value, "start.runup");
        if (list.size() > MAX_RUNUP) {
            throw new IllegalArgumentException("start.runup must have at most " + MAX_RUNUP
                    + " values, got " + list.size());
        }
        List<Integer> runup = new ArrayList<Integer>(list.size());
        int previous = -1;
        for (int i = 0; i < list.size(); i++) {
            int distance = integer(list.get(i), "start.runup[" + i + "]");
            if (distance < 0 || distance > MAX_RUNUP_DISTANCE) {
                throw new IllegalArgumentException("start.runup[" + i + "] must be in 0.."
                        + MAX_RUNUP_DISTANCE + ", got " + distance);
            }
            if (i == 0 && distance != 0) {
                throw new IllegalArgumentException("start.runup must start at 0, got " + distance);
            }
            if (distance <= previous) {
                throw new IllegalArgumentException("start.runup must be strictly increasing");
            }
            previous = distance;
            runup.add(distance);
        }
        return runup;
    }

    private static List<String> tags(Object value) {
        List<?> list = array(value, "tags");
        if (list.isEmpty()) {
            throw new IllegalArgumentException("tags must not be empty");
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (int i = 0; i < list.size(); i++) {
            String tag = string(list.get(i), "tags[" + i + "]");
            if (!NAME.matcher(tag).matches()) {
                throw new IllegalArgumentException("tags[" + i + "] must match [a-z0-9_]+, got \""
                        + tag + "\"");
            }
            if (!unique.add(tag)) {
                throw new IllegalArgumentException("duplicate tag \"" + tag + "\"");
            }
        }
        return new ArrayList<String>(unique);
    }

    private static int[] intTriple(Object value, String where) {
        List<?> list = array(value, where);
        if (list.size() != 3) {
            throw new IllegalArgumentException(where + " must have exactly 3 integers, got "
                    + list.size());
        }
        return new int[] {
                integer(list.get(0), where + "[0]"),
                integer(list.get(1), where + "[1]"),
                integer(list.get(2), where + "[2]")
        };
    }

    private static int integer(Object value, String where) {
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException(where + " must be an integer, got " + value);
        }
        double number = ((Double) value).doubleValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                || number != Math.rint(number)) {
            throw new IllegalArgumentException(where + " must be a 32-bit integer, got "
                    + value);
        }
        return (int) number;
    }

    private static String string(Object value, String where) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(where + " must be a non-empty string");
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String where) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(where + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static List<?> array(Object value, String where) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(where + " must be a JSON array");
        }
        return (List<?>) value;
    }

    private static void requireKeys(Map<String, Object> map, Set<String> expected, String where) {
        if (map.keySet().equals(expected)) {
            return;
        }
        Set<String> missing = new LinkedHashSet<String>(expected);
        missing.removeAll(map.keySet());
        Set<String> extra = new LinkedHashSet<String>(map.keySet());
        extra.removeAll(expected);
        throw new IllegalArgumentException(where + " keys must be " + expected
                + " (missing " + missing + ", extra " + extra + ")");
    }

    private static Set<String> keys(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(names)));
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
        return chars.toString();
    }
}
