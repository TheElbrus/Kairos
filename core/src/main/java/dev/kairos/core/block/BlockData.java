package dev.kairos.core.block;

import dev.kairos.core.scenario.Json;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of {@code data/1.8.9/blocks.json}, the block registry dump produced by
 * the loader's {@code /kairos dumpblocks} (task 1.0, schema 2 since task 1.4; see the
 * loader's {@code dev.kairos.loader.BlockDumper} class comment for the schema and how the
 * facts are obtained from the game).
 *
 * <p>Pure data: parsing, validation and lookup only. No physics, no movement rules, no
 * solver -- those arrive in tasks 1.2-1.4 and this class must not grow any (AGENTS.md:
 * {@code core} never imports {@code net.minecraft.*}; this package imports only the
 * hand-written {@link Json}).
 *
 * <p>Model: block name -> list of states; each block has the generated connector facts
 * (fence/wall/pane connection predicate inputs), and each state has its property map, the
 * collision boxes the game's entity-vs-block collision uses for a lone block (relative to
 * the block origin, {@code [minX,minY,minZ,maxX,maxY,maxZ]}, in the order the game emits
 * them), the raw {@code float} slipperiness, the climbable flag and the per-side
 * {@code isSideSolid} mask.
 *
 * <p>{@link #parse(String)} validates structure and that the header counts equal the
 * actual contents, so a truncated or hand-edited dump cannot load silently.
 */
public final class BlockData {

    /** Schema version this build understands. */
    public static final int SCHEMA = 2;

    private final String mcVersion;
    private final int declaredBlockCount;
    private final int declaredStateCount;
    private final Map<String, List<State>> blocks;

    private BlockData(String mcVersion, int declaredBlockCount, int declaredStateCount,
            Map<String, List<State>> blocks) {
        this.mcVersion = mcVersion;
        this.declaredBlockCount = declaredBlockCount;
        this.declaredStateCount = declaredStateCount;
        this.blocks = blocks;
    }

    /** Reads and parses a dump file. */
    public static BlockData load(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        // The dump is UTF-8 with LF only; a BOM would break the JSON parser and is a bug,
        // so no BOM stripping happens here on purpose.
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException on malformed JSON, wrong schema or count mismatch */
    public static BlockData parse(String text) {
        Map<String, Object> root = asObject(Json.parse(text), "(root)");

        int schema = intField(root, "schema", "(root)");
        if (schema != SCHEMA) {
            throw new IllegalArgumentException("(root).schema: unsupported schema " + schema
                    + " (this build understands " + SCHEMA + ")");
        }
        String mcVersion = stringField(root, "mc", "(root)");

        Map<String, Object> counts = asObject(root.get("counts"), "(root).counts");
        int declaredBlocks = intField(counts, "blocks", "(root).counts");
        int declaredStates = intField(counts, "states", "(root).counts");

        List<Object> blockList = asArray(root.get("blocks"), "(root).blocks");
        Map<String, List<State>> blocks = new LinkedHashMap<String, List<State>>();
        int stateCount = 0;
        for (int i = 0; i < blockList.size(); i++) {
            String where = "blocks[" + i + "]";
            Map<String, Object> block = asObject(blockList.get(i), where);
            String name = stringField(block, "name", where);
            if (blocks.containsKey(name)) {
                throw new IllegalArgumentException(where + ": duplicate block \"" + name + "\"");
            }
            Connector connector = parseConnector(block, where);
            List<Object> stateList = asArray(block.get("states"), where + ".states");
            List<State> states = new ArrayList<State>(stateList.size());
            for (int j = 0; j < stateList.size(); j++) {
                states.add(parseState(asObject(stateList.get(j), where + ".states[" + j + "]"),
                        where + ".states[" + j + "]", connector));
            }
            stateCount += states.size();
            blocks.put(name, Collections.unmodifiableList(states));
        }

        if (blocks.size() != declaredBlocks || stateCount != declaredStates) {
            throw new IllegalArgumentException("counts mismatch: header says " + declaredBlocks
                    + " blocks / " + declaredStates + " states but the data contains "
                    + blocks.size() + " / " + stateCount);
        }
        return new BlockData(mcVersion, declaredBlocks, declaredStates,
                Collections.unmodifiableMap(blocks));
    }

    /**
     * The block-level connector facts (BlockFence.canConnectTo, BlockWall.canConnectTo,
     * BlockPane.canPaneConnectToBlock): generated by the dumper from the live classes,
     * never hand-written.
     */
    private static Connector parseConnector(Map<String, Object> block, String where) {
        String at = where + ".connector";
        Map<String, Object> map = asObject(block.get("connector"), at);
        boolean cube = booleanField(map, "cube", at);
        boolean pane = booleanField(map, "pane", at);
        String kind = map.containsKey("kind") ? stringField(map, "kind", at) : null;
        String group = map.containsKey("group") ? stringField(map, "group", at) : null;
        return new Connector(kind, group, cube, pane);
    }

    private static State parseState(Map<String, Object> map, String where,
            Connector connector) {
        Map<String, Object> propsRaw = asObject(map.get("props"), where + ".props");
        Map<String, String> props = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : propsRaw.entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException(where + ".props." + entry.getKey()
                        + ": expected a string, got " + entry.getValue());
            }
            props.put(entry.getKey(), (String) entry.getValue());
        }

        List<Object> boxesRaw = asArray(map.get("boxes"), where + ".boxes");
        List<Box> boxes = new ArrayList<Box>(boxesRaw.size());
        for (int i = 0; i < boxesRaw.size(); i++) {
            List<Object> values = asArray(boxesRaw.get(i), where + ".boxes[" + i + "]");
            if (values.size() != 6) {
                throw new IllegalArgumentException(where + ".boxes[" + i + "]: expected 6 numbers"
                        + " (minX,minY,minZ,maxX,maxY,maxZ), got " + values.size());
            }
            for (int k = 0; k < 6; k++) {
                if (!(values.get(k) instanceof Double)) {
                    throw new IllegalArgumentException(where + ".boxes[" + i + "][" + k
                            + "]: expected a number, got " + values.get(k));
                }
            }
            boxes.add(new Box((Double) values.get(0), (Double) values.get(1),
                    (Double) values.get(2), (Double) values.get(3),
                    (Double) values.get(4), (Double) values.get(5)));
        }

        // Double -> float: JSON has one number type, but the dump wrote Float.toString of
        // the raw float, so the narrowing is exact (BlockDumper writes block.slipperiness).
        float slipperiness = (float) numberField(map, "slipperiness", where);
        Object climbable = map.get("climbable");
        if (!(climbable instanceof Boolean)) {
            throw new IllegalArgumentException(where + ".climbable: expected a boolean, got "
                    + climbable);
        }
        int solidSides = intField(map, "solid", where);
        if (solidSides < 0 || solidSides > 63) {
            throw new IllegalArgumentException(where + ".solid: expected a 6-bit mask (0-63),"
                    + " got " + solidSides);
        }
        return new State(props, boxes, slipperiness, (Boolean) climbable, connector, solidSides);
    }

    public String mcVersion() {
        return this.mcVersion;
    }

    /** Number of blocks actually present. */
    public int blockCount() {
        return this.blocks.size();
    }

    /** Number of states actually present (the sum over all blocks). */
    public int stateCount() {
        int count = 0;
        for (List<State> states : this.blocks.values()) {
            count += states.size();
        }
        return count;
    }

    /** The count declared in the file header. */
    public int declaredBlockCount() {
        return this.declaredBlockCount;
    }

    /** The state count declared in the file header. */
    public int declaredStateCount() {
        return this.declaredStateCount;
    }

    /** All block names, in file order (sorted by registry name). */
    public List<String> blockNames() {
        return Collections.unmodifiableList(new ArrayList<String>(this.blocks.keySet()));
    }

    /** States of one block in file order, or {@code null} when the name is unknown. */
    public List<State> states(String blockName) {
        return this.blocks.get(blockName);
    }

    /**
     * The state of {@code blockName} whose properties equal {@code props} exactly, or
     * {@code null} when the block is unknown or no state matches.
     */
    public State state(String blockName, Map<String, String> props) {
        List<State> candidates = this.blocks.get(blockName);
        if (candidates == null) {
            return null;
        }
        for (State state : candidates) {
            if (state.props.equals(props)) {
                return state;
            }
        }
        return null;
    }

    /**
     * The generated connection facts of one block (task 1.4). {@code kind} is
     * {@code "fence"}, {@code "wall"}, {@code "pane"}, {@code "gate"} or {@code null} for
     * every other block; {@code group} identifies the blocks that connect to each other
     * for fences (same {@code Material}) and walls (same {@code Block} instance), and is
     * {@code null} otherwise. {@code cube} is the {@code canConnectTo} full-cube test
     * ({@code material.isOpaque() && isFullCube() && material != gourd});
     * {@code pane} is {@code BlockPane.canPaneConnectToBlock}.
     */
    public static final class Connector {
        public final String kind;
        public final String group;
        public final boolean cube;
        public final boolean pane;

        private Connector(String kind, String group, boolean cube, boolean pane) {
            this.kind = kind;
            this.group = group;
            this.cube = cube;
            this.pane = pane;
        }
    }

    /** A single block state as dumped. */
    public static final class State {
        /** Property name -> value name, in the dump's (sorted) order; unmodifiable. */
        public final Map<String, String> props;
        /** Collision boxes for a lone block, relative to the block origin; unmodifiable. */
        public final List<Box> boxes;
        /** Raw {@code Block.slipperiness} (Block.java:321). */
        public final float slipperiness;
        /** {@code Block.isLadder} (Block.java:1250). */
        public final boolean climbable;
        /** Block-level connection facts, shared by all states of the block. */
        public final Connector connector;
        /**
         * {@code Block.isSideSolid} for this state as a 6-bit mask, bit
         * {@code EnumFacing.ordinal()}: DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5
         * (Block.java:1262, state-dependent for slabs/stairs/snow/...).
         */
        public final int solidSides;

        private State(Map<String, String> props, List<Box> boxes, float slipperiness,
                boolean climbable, Connector connector, int solidSides) {
            this.props = Collections.unmodifiableMap(props);
            this.boxes = Collections.unmodifiableList(boxes);
            this.slipperiness = slipperiness;
            this.climbable = climbable;
            this.connector = connector;
            this.solidSides = solidSides;
        }
    }

    /** An axis-aligned box in block-local coordinates. */
    public static final class Box {
        public final double minX;
        public final double minY;
        public final double minZ;
        public final double maxX;
        public final double maxY;
        public final double maxZ;

        private Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        @Override
        public String toString() {
            return "[" + this.minX + ", " + this.minY + ", " + this.minZ + ", "
                    + this.maxX + ", " + this.maxY + ", " + this.maxZ + "]";
        }
    }

    // --- JSON helpers -----------------------------------------------------------

    private static Map<String, Object> asObject(Object value, String where) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(where + ": expected an object, got " + value);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static List<Object> asArray(Object value, String where) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(where + ": expected an array, got " + value);
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    private static String stringField(Map<String, Object> map, String key, String where) {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(where + "." + key + ": expected a string, got "
                    + value);
        }
        return (String) value;
    }

    private static boolean booleanField(Map<String, Object> map, String key, String where) {
        Object value = map.get(key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(where + "." + key + ": expected a boolean, got "
                    + value);
        }
        return (Boolean) value;
    }

    private static double numberField(Map<String, Object> map, String key, String where) {
        Object value = map.get(key);
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException(where + "." + key + ": expected a number, got "
                    + value);
        }
        return (Double) value;
    }

    private static int intField(Map<String, Object> map, String key, String where) {
        double value = numberField(map, key, where);
        if (value != Math.rint(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(where + "." + key + ": expected an integer, got "
                    + value);
        }
        return (int) value;
    }
}
