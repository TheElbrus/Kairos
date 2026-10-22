package dev.kairos.core.fuzz;

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
import java.util.List;

/**
 * Locates and validates the committed fuzz corpus (task 1.7.1): exactly
 * {@link #EXPECTED_COUNT} compact scenario JSON lines in {@link #RELATIVE_PATH}, UTF-8
 * without a BOM, LF-only, no blank lines.
 *
 * <p>Pure Java (AGENTS.md rule 1). The path is found by walking up from a start directory
 * (the loader passes {@code Minecraft.mcDataDir}) to the repo-root marker
 * {@code settings.gradle}; the optional {@code -Dkairos.fuzzScenarios=<file>} override is
 * supported for tests and experiments. An error lists every path that was searched.
 *
 * <p>The corpus is data, not generated at runtime: {@code FuzzScenarioGenerator} (test
 * sources) defines it, {@code FuzzScenarioDataTest} pins the committed text, and the
 * batch recorder only reads this class.
 */
public final class FuzzScenarios {

    /** The committed corpus always has exactly this many lines. */
    public static final int EXPECTED_COUNT = 1000;

    /** Path of the corpus relative to the repository root. */
    public static final String RELATIVE_PATH = "data/fuzz/scenarios.jsonl";

    /** Optional system property naming an explicit corpus file. */
    public static final String OVERRIDE_PROPERTY = "kairos.fuzzScenarios";

    private static final String ROOT_MARKER = "settings.gradle";

    private FuzzScenarios() {
    }

    /** The exact corpus text, decoded as UTF-8 (no BOM is stripped). */
    public static String readText(File startDir) throws IOException {
        byte[] bytes = Files.readAllBytes(locate(startDir).toPath());
        try {
            return decode(bytes);
        } catch (CharacterCodingException e) {
            throw new IOException("fuzz corpus is not valid UTF-8: " + e.getMessage(), e);
        }
    }

    /**
     * The corpus lines, without the terminating LF of each line.
     *
     * @throws IOException if the file is missing, not UTF-8, has a BOM or CR, does not end
     *         with LF, or does not consist of exactly {@link #EXPECTED_COUNT} non-empty
     *         lines
     */
    public static List<String> lines(File startDir) throws IOException {
        byte[] bytes = Files.readAllBytes(locate(startDir).toPath());
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            throw new IOException("fuzz corpus must not start with a UTF-8 BOM: "
                    + RELATIVE_PATH);
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0x0D) {
                throw new IOException("fuzz corpus must be LF-only, found CR at byte "
                        + i + ": " + RELATIVE_PATH);
            }
        }

        String text;
        try {
            text = decode(bytes);
        } catch (CharacterCodingException e) {
            throw new IOException("fuzz corpus is not valid UTF-8: " + e.getMessage(), e);
        }
        if (!text.endsWith("\n")) {
            throw new IOException("fuzz corpus must end with LF: " + RELATIVE_PATH);
        }
        String body = text.substring(0, text.length() - 1);
        String[] split = body.split("\n", -1);
        if (split.length != EXPECTED_COUNT) {
            throw new IOException("fuzz corpus must have exactly " + EXPECTED_COUNT
                    + " lines, found " + split.length + ": " + RELATIVE_PATH);
        }
        List<String> lines = new ArrayList<String>(split.length);
        for (int i = 0; i < split.length; i++) {
            if (split[i].isEmpty()) {
                throw new IOException("fuzz corpus line " + (i + 1) + " is blank: "
                        + RELATIVE_PATH);
            }
            lines.add(split[i]);
        }
        return Collections.unmodifiableList(lines);
    }

    /** The corpus file, or an {@link IOException} listing every path that was searched. */
    private static File locate(File startDir) throws IOException {
        List<String> searched = new ArrayList<String>();

        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            File file = new File(override.trim());
            if (!file.isAbsolute()) {
                file = new File(startDir == null ? new File(".") : startDir, override.trim());
            }
            searched.add(file.getPath());
            if (file.isFile()) {
                return file;
            }
            throw new IOException("no fuzz corpus at the -D" + OVERRIDE_PROPERTY
                    + " path; searched: " + searched);
        }

        File start = canonicalOrAbsolute(startDir);
        for (File dir = start; dir != null; dir = dir.getParentFile()) {
            if (new File(dir, ROOT_MARKER).isFile()) {
                File candidate = new File(dir, RELATIVE_PATH);
                searched.add(candidate.getPath());
                if (candidate.isFile()) {
                    return candidate;
                }
                break;   // the first marker is the repo root
            }
        }
        throw new IOException("no " + RELATIVE_PATH + " found; searched: " + searched);
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
        return chars.toString();
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
