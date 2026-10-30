package dev.kairos.core.replay;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.solver.BeamSearchConfig;
import dev.kairos.core.solver.BeamSolver;
import dev.kairos.core.solver.JumpPlan;
import dev.kairos.core.solver.JumpTarget;
import dev.kairos.core.solver.SolveResult;
import dev.kairos.core.trace.TraceInput;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Deterministic source generator for the task 2.8 200-plan replay corpus.
 *
 * <p>The recipe has no RNG: gaps {@code 1..4} x height delta {@code {0, -1}} x start yaw
 * {@code {-20, -10, 0, 10, 20}} x lateral start x {@code {0.5, 0.9, 1.3, 1.7, 2.1}}, in
 * that nesting order, named {@code sample_0000..sample_0199}. Each plan is solved with
 * {@link BeamSolver} (width {@value #BEAM_WIDTH}, yaw delta {@value #YAW_DELTA}, tick
 * budget {@value #TICK_BUDGET}) and independently replayed before it is emitted; a plan
 * that does not land throws instead of producing a corpus line.
 *
 * <p>The start state is the teleport state, not the requested point: the game builds the
 * player box around the requested position and then re-derives {@code posX}/{@code posZ}
 * as the box midpoint ({@code Entity.resetPositionToBB}, Entity.java:756-760, as already
 * documented in {@link MovementSim#initialState}). For x = 0.9 that midpoint is one ULP
 * below 0.9, and the solver must start from the same box the game will use.
 *
 * <p>This class lives in the test sources on purpose: the corpus is data, pinned by
 * {@code SampleCorpusDataTest}, and the batch recorder only reads
 * {@link ReplayScenarios}.
 */
public final class SampleCorpusGenerator {

    /** Number of plans in the corpus. */
    public static final int COUNT = ReplayScenarios.EXPECTED_COUNT;

    static final int[] GAPS = {1, 2, 3, 4};
    static final int[] HEIGHT_DELTAS = {0, -1};
    static final float[] START_YAWS = {-20.0f, -10.0f, 0.0f, 10.0f, 20.0f};
    static final double[] START_XS = {0.5, 0.9, 1.3, 1.7, 2.1};

    static final double START_Y = 65.0;
    static final double START_Z = 0.5;
    static final int SETTLE_TICKS = 10;
    static final int BEAM_WIDTH = 512;
    static final float YAW_DELTA = 15.0f;
    static final int TICK_BUDGET = 80;

    /** Settled ground state: {@code Block.onLanded} zeroed motionY, then one gravity tick. */
    private static final double REST_VY = -0.0784000015258789;

    private static final String STONE = "\"id\":\"stone\",\"props\":{\"variant\":\"stone\"}";

    private static final BlockData BLOCKS = loadBlocks();

    private SampleCorpusGenerator() {
    }

    /** The exact corpus text: {@link #COUNT} compact JSON lines, each terminated by LF. */
    public static String generateAll() {
        StringBuilder out = new StringBuilder(COUNT * 4096);
        for (int i = 0; i < COUNT; i++) {
            appendScenario(out, i);
            out.append('\n');
        }
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        String text = generateAll();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        File target = new File(repoRoot(), ReplayScenarios.RELATIVE_PATH);
        Files.write(target.toPath(), bytes);
        System.out.println("wrote " + bytes.length + " bytes, " + COUNT + " lines to "
                + target.getPath());
    }

    static int gap(int index) {
        requireIndex(index);
        return index / 50 + 1;
    }

    static int heightDelta(int index) {
        requireIndex(index);
        return (index % 50) >= 25 ? -1 : 0;
    }

    static float startYaw(int index) {
        requireIndex(index);
        return START_YAWS[(index % 25) / 5];
    }

    static double startX(int index) {
        requireIndex(index);
        return START_XS[index % 5];
    }

    static String name(int index) {
        requireIndex(index);
        StringBuilder name = new StringBuilder("sample_");
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

    /** The teleport start state: box from the requested x, position from its midpoint. */
    static MovementSim.State start(double x) {
        MovementSim.State state = MovementSim.initialState(x, START_Y, START_Z,
                0.0, REST_VY, 0.0, true, false, false, true);
        state.x = (state.minX + state.maxX) / 2.0;
        state.z = (state.minZ + state.maxZ) / 2.0;
        return state;
    }

    /** The recipe target: {@code [x-0.5, x+0.5] x [gap+1, gap+2]} at {@code 65+dy}. */
    static JumpTarget target(int gap, int dy, double x) {
        return JumpTarget.of(x - 0.5, x + 0.5, gap + 1, gap + 2, 65.0 + dy);
    }

    static BlockData blockData() {
        return BLOCKS;
    }

    // --- one plan -------------------------------------------------------------------

    private static void appendScenario(StringBuilder out, int index) {
        int gap = gap(index);
        int dy = heightDelta(index);
        float yaw = startYaw(index);
        double x = startX(index);
        String name = name(index);

        Scenario source = sourceScenario(gap, dy, x, yaw);
        CollisionWorld world = CollisionWorld.of(BLOCKS, source);
        JumpTarget target = target(gap, dy, x);
        SolveResult result = BeamSolver.solve(start(x), target, world,
                BeamSearchConfig.of(yaw, YAW_DELTA, BEAM_WIDTH, TICK_BUDGET));
        if (!result.isSolved()) {
            throw new IllegalStateException(name + " has no plan within " + TICK_BUDGET
                    + " ticks: " + result.reason);
        }
        JumpPlan plan = result.plan;
        verifyReplay(name, x, world, plan, target);
        appendJson(out, name, gap, dy, x, yaw, plan);
    }

    /**
     * Independent replay from a fresh start state. The beam already replays its candidate,
     * but the committed scenario is expressed as raw keys, so a plan using sneak (whose
     * 0.3 axis scaling ScenarioInput cannot carry) or non-key axes is refused here.
     */
    private static void verifyReplay(String name, double startX, CollisionWorld world,
            JumpPlan plan, JumpTarget target) {
        MovementSim.State state = start(startX);
        for (TraceInput in : plan.inputs) {
            requireScenarioExpressible(name, in);
            MovementSim.step(state, in, world);
        }
        if (!target.isReachedBy(state)) {
            throw new IllegalStateException(name + " replay did not land on " + target
                    + "; final state=(" + state.x + "," + state.y + "," + state.z + ")");
        }
    }

    private static void requireScenarioExpressible(String name, TraceInput in) {
        if (in.sneak != 0) {
            throw new IllegalStateException(name + " plan uses sneak, but ScenarioInput "
                    + "only carries raw keys and the game scales those by 0.3");
        }
        float mf = (in.w != 0 ? 1.0f : 0.0f) - (in.s != 0 ? 1.0f : 0.0f);
        float ms = (in.a != 0 ? 1.0f : 0.0f) - (in.d != 0 ? 1.0f : 0.0f);
        if (Float.compare(in.mf, mf) != 0 || Float.compare(in.ms, ms) != 0) {
            throw new IllegalStateException(name + " plan axes mf=" + in.mf + " ms=" + in.ms
                    + " cannot be expressed by keys w/a/s/d");
        }
    }

    private static void appendJson(StringBuilder out, String name, int gap, int dy,
            double x, float yaw, JumpPlan plan) {
        out.append("{\"name\":\"").append(name).append("\",\"start\":{\"pos\":[")
                .append(Double.toString(x)).append(',')
                .append(Double.toString(START_Y)).append(',')
                .append(Double.toString(START_Z))
                .append("],\"yaw\":").append(Float.toString(yaw))
                .append(",\"pitch\":0.0},\"blocks\":[").append(blocksJson(gap, dy))
                .append("],\"settleTicks\":").append(SETTLE_TICKS).append(",\"inputs\":[");
        for (int i = 0; i < plan.inputs.size(); i++) {
            TraceInput in = plan.inputs.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"ticks\":1,\"w\":").append(in.w)
                    .append(",\"a\":").append(in.a)
                    .append(",\"s\":").append(in.s)
                    .append(",\"d\":").append(in.d)
                    .append(",\"jump\":").append(in.jump)
                    .append(",\"sneak\":").append(in.sneak)
                    .append(",\"sprint\":").append(in.sprint)
                    .append(",\"yaw\":").append(Float.toString(in.yaw))
                    .append('}');
        }
        out.append("]}");
    }

    private static String blocksJson(int gap, int dy) {
        return "{\"from\":[-3,64,-20],\"to\":[3,64,0]," + STONE + "},"
                + "{\"from\":[-3," + (64 + dy) + ',' + (gap + 1) + "],\"to\":[3,"
                + (64 + dy) + ',' + (gap + 7) + "]," + STONE + "}";
    }

    private static Scenario sourceScenario(int gap, int dy, double x, float yaw) {
        return ScenarioParser.parse("{\"name\":\"sample_source\",\"start\":{\"pos\":["
                + Double.toString(x) + ",65.0,0.5],\"yaw\":" + Float.toString(yaw)
                + "},\"blocks\":[" + blocksJson(gap, dy) + "],\"inputs\":[{\"ticks\":1}]}");
    }

    private static void requireIndex(int index) {
        if (index < 0 || index >= COUNT) {
            throw new IllegalArgumentException(
                    "index must be 0.." + (COUNT - 1) + ", got " + index);
        }
    }

    static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return f;
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }

    private static BlockData loadBlocks() {
        try {
            return BlockData.load(new File(repoRoot(), "data/1.8.9/blocks.json"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot load data/1.8.9/blocks.json", e);
        }
    }
}
