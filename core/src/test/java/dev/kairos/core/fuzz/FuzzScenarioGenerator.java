package dev.kairos.core.fuzz;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic generator for the committed 1000-scenario fuzz corpus (task 1.7.1).
 *
 * <p>This class lives in the test sources on purpose: the corpus is data, pinned by
 * {@code FuzzScenarioDataTest}, and the batch runner must never generate scenarios at
 * runtime ({@code data/fuzz/scenarios.jsonl} is the source of truth for task 1.7.2).
 *
 * <p>The only entropy is a single {@link Random} with the fixed master seed
 * {@link #MASTER_SEED}, consumed sequentially in corpus order. There is no
 * {@code Math.random}, clock, hash iteration or locale-sensitive formatting, so
 * {@link #generateAll()} is byte-stable on every JVM.
 *
 * <p>Arena per scenario (PLAN.md Phase 1 task 1.7): stone floor at y=64 over x,z
 * -24..24; air cleared at y=65..68 over the same footprint; stone walls at x=+-24 and
 * z=+-24, y=65..67; random obstacle columns/covers inside x,z -16..16 and floating
 * ceiling blocks at y=68 inside x,z -10..10, never in the 5x5 start clearing; ice or
 * packed ice replaces scattered floor cells. Regions are disjoint by construction, so
 * the existing per-region build/restore capture in the loader stays correct.
 */
public final class FuzzScenarioGenerator {

    /** Number of scenarios in the corpus. */
    public static final int COUNT = 1000;

    /** Recorded ticks per scenario. */
    public static final int TICKS = 100;

    /**
     * Fixed master seed, ASCII "KAIROS17": the single {@link Random} is created with it
     * and consumed sequentially for scenarios {@code fuzz_0000}..{@code fuzz_0999}.
     */
    public static final long MASTER_SEED = 0x4B4149524F533137L;

    private static final int RADIUS = 24;
    private static final int SIZE = 2 * RADIUS + 1;
    private static final int START_RADIUS = 2;

    private static final String[] FACINGS = {"north", "south", "east", "west"};
    private static final String[] CARPET_COLORS = {"red", "blue", "white"};

    private static final String STONE_PROPS = "{\"variant\":\"stone\"}";
    private static final String SLAB_PROPS = "{\"half\":\"bottom\",\"variant\":\"stone\"}";
    private static final String FENCE_PROPS =
            "{\"east\":\"false\",\"north\":\"false\",\"south\":\"false\",\"west\":\"false\"}";
    private static final String WALL_PROPS = "{\"east\":\"false\",\"north\":\"false\","
            + "\"south\":\"false\",\"up\":\"false\",\"variant\":\"cobblestone\",\"west\":\"false\"}";
    private static final String PANE_PROPS = FENCE_PROPS;

    private FuzzScenarioGenerator() {
    }

    /** The exact corpus text: {@link #COUNT} compact JSON lines, each terminated by LF. */
    public static String generateAll() {
        Random random = new Random(MASTER_SEED);
        StringBuilder out = new StringBuilder(6 * 1024 * 1024);
        for (int i = 0; i < COUNT; i++) {
            appendScenario(out, i, random);
            out.append('\n');
        }
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: FuzzScenarioGenerator <out.jsonl>");
            System.exit(2);
        }
        String text = generateAll();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Files.write(new File(args[0]).toPath(), bytes);
        System.out.println("wrote " + bytes.length + " bytes, " + COUNT + " lines to " + args[0]);
    }

    // --- one scenario ---------------------------------------------------------------

    private static void appendScenario(StringBuilder out, int index, Random random) {
        int yawMilli = random.nextInt(360000);           // [0, 360) in 0.001-degree steps

        // Solid occupancy of the 4 cleared layers (y=65..68); everything else is air.
        boolean[][][] solid = new boolean[4][SIZE][SIZE];
        markWalls(solid);

        // Feature cells are exclusive: a column and a cover never share an x,z cell.
        boolean[][] used = new boolean[SIZE][SIZE];
        Blocks features = new Blocks();

        int columns = 10 + random.nextInt(15);           // 10..24
        for (int i = 0; i < columns; i++) {
            int[] cell = freeCell(random, used, 16);
            if (cell == null) {
                break;
            }
            used[cell[1]][cell[0]] = true;
            int height = 1 + random.nextInt(3);          // 1..3
            int kind = random.nextInt(6);
            String id;
            String props;
            switch (kind) {
                case 0:
                    id = "stone";
                    props = STONE_PROPS;
                    break;
                case 1:
                    id = "stone_slab";
                    props = SLAB_PROPS;
                    break;
                case 2:
                    id = "oak_stairs";
                    props = "{\"facing\":\"" + FACINGS[random.nextInt(FACINGS.length)]
                            + "\",\"half\":\"bottom\",\"shape\":\"straight\"}";
                    break;
                case 3:
                    id = "fence";
                    props = FENCE_PROPS;
                    break;
                case 4:
                    id = "cobblestone_wall";
                    props = WALL_PROPS;
                    break;
                default:
                    id = "glass_pane";
                    props = PANE_PROPS;
                    break;
            }
            for (int layer = 0; layer < height; layer++) {
                solid[layer][cell[1]][cell[0]] = true;
            }
            features.add(cell[0] - RADIUS, 65, cell[1] - RADIUS,
                    cell[0] - RADIUS, 64 + height, cell[1] - RADIUS, id, props);
        }

        int covers = random.nextInt(13);                 // 0..12
        for (int i = 0; i < covers; i++) {
            int[] cell = freeCell(random, used, 16);
            if (cell == null) {
                break;
            }
            used[cell[1]][cell[0]] = true;
            solid[0][cell[1]][cell[0]] = true;
            int kind = random.nextInt(3);
            if (kind == 0) {
                features.add(cell[0] - RADIUS, 65, cell[1] - RADIUS,
                        cell[0] - RADIUS, 65, cell[1] - RADIUS, "carpet",
                        "{\"color\":\"" + CARPET_COLORS[random.nextInt(CARPET_COLORS.length)] + "\"}");
            } else if (kind == 1) {
                features.add(cell[0] - RADIUS, 65, cell[1] - RADIUS,
                        cell[0] - RADIUS, 65, cell[1] - RADIUS, "snow_layer",
                        "{\"layers\":\"" + (random.nextBoolean() ? 2 : 4) + "\"}");
            } else {
                features.add(cell[0] - RADIUS, 65, cell[1] - RADIUS,
                        cell[0] - RADIUS, 65, cell[1] - RADIUS, "iron_trapdoor",
                        "{\"facing\":\"" + FACINGS[random.nextInt(FACINGS.length)]
                                + "\",\"half\":\"bottom\",\"open\":\"false\"}");
            }
        }

        int ceilings = random.nextInt(6);                // 0..5
        for (int i = 0; i < ceilings; i++) {
            int[] cell = freeCell(random, used, 10);
            if (cell == null) {
                break;
            }
            used[cell[1]][cell[0]] = true;
            solid[3][cell[1]][cell[0]] = true;
            features.add(cell[0] - RADIUS, 68, cell[1] - RADIUS,
                    cell[0] - RADIUS, 68, cell[1] - RADIUS, "stone", STONE_PROPS);
        }

        // Ice replaces floor cells (y=64 only), so it never overlaps air or features.
        boolean[][] ice = new boolean[SIZE][SIZE];
        boolean[][] packed = new boolean[SIZE][SIZE];
        int iceCount = random.nextInt(7);                // 0..6
        int placed = 0;
        while (placed < iceCount) {
            int gx = random.nextInt(SIZE);
            int gz = random.nextInt(SIZE);
            if (ice[gz][gx]) {
                continue;
            }
            ice[gz][gx] = true;
            packed[gz][gx] = random.nextBoolean();
            placed++;
        }

        Blocks floor = new Blocks();
        appendStoneFloor(floor, ice);

        Blocks air = new Blocks();
        appendAir(air, solid);

        Blocks walls = new Blocks();
        walls.add(-24, 65, -24, 24, 67, -24, "stone", STONE_PROPS);
        walls.add(-24, 65, 24, 24, 67, 24, "stone", STONE_PROPS);
        walls.add(-24, 65, -23, -24, 67, 23, "stone", STONE_PROPS);
        walls.add(24, 65, -23, 24, 67, 23, "stone", STONE_PROPS);

        Blocks iceBlocks = new Blocks();
        for (int gz = 0; gz < SIZE; gz++) {
            for (int gx = 0; gx < SIZE; gx++) {
                if (ice[gz][gx]) {
                    iceBlocks.add(gx - RADIUS, 64, gz - RADIUS,
                            gx - RADIUS, 64, gz - RADIUS,
                            packed[gz][gx] ? "packed_ice" : "ice", null);
                }
            }
        }

        out.append("{\"name\":\"").append(name(index))
                .append("\",\"start\":{\"pos\":[0.5,65.0,0.5],\"yaw\":")
                .append(Double.toString(yawMilli / 1000.0))
                .append(",\"pitch\":0.0},\"blocks\":[");
        floor.appendTo(out);
        if (air.size() > 0) {
            if (floor.size() > 0) {
                out.append(',');
            }
            air.appendTo(out);
        }
        if (walls.size() > 0) {
            if (floor.size() + air.size() > 0) {
                out.append(',');
            }
            walls.appendTo(out);
        }
        if (features.size() > 0) {
            if (floor.size() + air.size() + walls.size() > 0) {
                out.append(',');
            }
            features.appendTo(out);
        }
        if (iceBlocks.size() > 0) {
            if (floor.size() + air.size() + walls.size() + features.size() > 0) {
                out.append(',');
            }
            iceBlocks.appendTo(out);
        }
        out.append("],\"settleTicks\":10");
        appendInputs(out, random);
        out.append('}');
    }

    // --- arena helpers --------------------------------------------------------------

    private static void markWalls(boolean[][][] solid) {
        for (int layer = 0; layer < 3; layer++) {
            for (int i = 0; i < SIZE; i++) {
                solid[layer][0][i] = true;
                solid[layer][SIZE - 1][i] = true;
                solid[layer][i][0] = true;
                solid[layer][i][SIZE - 1] = true;
            }
        }
    }

    /**
     * A free cell in {@code [-radius, radius]^2}, never inside the 5x5 start clearing and
     * never a cell already used by another feature. Retries keep the draw deterministic:
     * the same random sequence always yields the same accepted cell.
     */
    private static int[] freeCell(Random random, boolean[][] used, int radius) {
        int width = 2 * radius + 1;
        for (int attempt = 0; attempt < 1000; attempt++) {
            int x = random.nextInt(width) - radius;
            int z = random.nextInt(width) - radius;
            if (Math.abs(x) <= START_RADIUS && Math.abs(z) <= START_RADIUS) {
                continue;
            }
            int gx = x + RADIUS;
            int gz = z + RADIUS;
            if (!used[gz][gx]) {
                return new int[] {gx, gz};
            }
        }
        return null;
    }

    /**
     * The stone floor at y=64 as z-bands of x-runs that exclude the ice cells. Bands with
     * the same runs merge vertically, so a scenario without ice has one floor region.
     */
    private static void appendStoneFloor(Blocks floor, boolean[][] ice) {
        List<int[]> pending = null;
        int bandStart = 0;
        for (int gz = 0; gz < SIZE; gz++) {
            List<int[]> runs = new ArrayList<int[]>();
            int gx = 0;
            while (gx < SIZE) {
                while (gx < SIZE && ice[gz][gx]) {
                    gx++;
                }
                if (gx >= SIZE) {
                    break;
                }
                int start = gx;
                while (gx < SIZE && !ice[gz][gx]) {
                    gx++;
                }
                runs.add(new int[] {start, gx - 1});
            }
            if (pending != null && sameRuns(pending, runs)) {
                continue;
            }
            if (pending != null) {
                appendFloorBand(floor, pending, bandStart, gz - 1);
            }
            pending = runs;
            bandStart = gz;
        }
        if (pending != null) {
            appendFloorBand(floor, pending, bandStart, SIZE - 1);
        }
    }

    private static void appendFloorBand(Blocks floor, List<int[]> runs, int fromZ, int toZ) {
        for (int[] run : runs) {
            floor.add(run[0] - RADIUS, 64, fromZ - RADIUS,
                    run[1] - RADIUS, 64, toZ - RADIUS, "stone", STONE_PROPS);
        }
    }

    private static boolean sameRuns(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Greedy box decomposition of the air complement. Growing x, then z, then y keeps the
     * open areas in a few large regions while the feature holes split only locally.
     */
    private static void appendAir(Blocks air, boolean[][][] solid) {
        boolean[][][] assigned = new boolean[4][SIZE][SIZE];
        for (int layer = 0; layer < 4; layer++) {
            for (int gz = 0; gz < SIZE; gz++) {
                for (int gx = 0; gx < SIZE; gx++) {
                    if (solid[layer][gz][gx] || assigned[layer][gz][gx]) {
                        continue;
                    }
                    int gx2 = gx;
                    while (gx2 + 1 < SIZE && !solid[layer][gz][gx2 + 1]
                            && !assigned[layer][gz][gx2 + 1]) {
                        gx2++;
                    }
                    int gz2 = gz;
                    while (gz2 + 1 < SIZE && freeRect(layer, layer, gx, gx2, gz2 + 1, gz2 + 1,
                            solid, assigned)) {
                        gz2++;
                    }
                    int layer2 = layer;
                    while (layer2 + 1 < 4 && freeRect(layer2 + 1, layer2 + 1, gx, gx2, gz, gz2,
                            solid, assigned)) {
                        layer2++;
                    }
                    for (int ly = layer; ly <= layer2; ly++) {
                        for (int z = gz; z <= gz2; z++) {
                            for (int x = gx; x <= gx2; x++) {
                                assigned[ly][z][x] = true;
                            }
                        }
                    }
                    air.add(gx - RADIUS, 65 + layer, gz - RADIUS,
                            gx2 - RADIUS, 65 + layer2, gz2 - RADIUS, "air", null);
                }
            }
        }
    }

    private static boolean freeRect(int layerFrom, int layerTo, int xFrom, int xTo,
            int zFrom, int zTo, boolean[][][] solid, boolean[][][] assigned) {
        for (int layer = layerFrom; layer <= layerTo; layer++) {
            for (int z = zFrom; z <= zTo; z++) {
                for (int x = xFrom; x <= xTo; x++) {
                    if (solid[layer][z][x] || assigned[layer][z][x]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // --- inputs ---------------------------------------------------------------------

    /**
     * Segments of 2..10 ticks (the last one truncated) with random movement flags. Zero
     * flags are omitted; the parser defaults them to 0, so the total is exactly
     * {@link #TICKS}.
     */
    private static void appendInputs(StringBuilder out, Random random) {
        out.append(",\"inputs\":[");
        int remaining = TICKS;
        boolean first = true;
        while (remaining > 0) {
            int ticks = 2 + random.nextInt(9);
            if (ticks > remaining) {
                ticks = remaining;
            }
            int mode = random.nextInt(10);
            boolean w = mode <= 2;
            boolean a = mode == 3 || mode == 6 || mode == 8;
            boolean s = mode == 4;
            boolean d = mode == 5 || mode == 7 || mode == 8;
            boolean jump = random.nextInt(100) < 22;
            boolean sneak = random.nextInt(100) < 18;
            boolean sprint = random.nextInt(100) < 35;

            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"ticks\":").append(ticks);
            if (w) {
                out.append(",\"w\":1");
            }
            if (a) {
                out.append(",\"a\":1");
            }
            if (s) {
                out.append(",\"s\":1");
            }
            if (d) {
                out.append(",\"d\":1");
            }
            if (jump) {
                out.append(",\"jump\":1");
            }
            if (sneak) {
                out.append(",\"sneak\":1");
            }
            if (sprint) {
                out.append(",\"sprint\":1");
            }
            out.append('}');
            remaining -= ticks;
        }
        out.append(']');
    }

    private static String name(int index) {
        StringBuilder name = new StringBuilder("fuzz_");
        if (index < 10) {
            name.append('0');
        }
        if (index < 100) {
            name.append('0');
        }
        if (index < 1000) {
            name.append('0');
        }
        return name.append(index).toString();
    }

    /** Small append-only JSON array of block regions. */
    private static final class Blocks {

        private final StringBuilder sb = new StringBuilder();
        private int size;

        void add(int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
                String id, String props) {
            if (this.size > 0) {
                this.sb.append(',');
            }
            this.size++;
            this.sb.append("{\"from\":[").append(fromX).append(',').append(fromY).append(',')
                    .append(fromZ).append("],\"to\":[").append(toX).append(',').append(toY)
                    .append(',').append(toZ).append("],\"id\":\"").append(id).append('"');
            if (props != null) {
                this.sb.append(",\"props\":").append(props);
            }
            this.sb.append('}');
        }

        int size() {
            return this.size;
        }

        void appendTo(StringBuilder out) {
            out.append(this.sb);
        }
    }
}
