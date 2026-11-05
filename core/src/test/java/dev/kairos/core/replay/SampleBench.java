package dev.kairos.core.replay;

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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Task 2.9 planning-speed baseline for the 200-plan corpus.
 *
 * <p>Solves every corpus plan with exactly the configuration
 * {@link SampleCorpusGenerator} used ({@link BeamSolver}, width
 * {@value SampleCorpusGenerator#BEAM_WIDTH}, yaw delta
 * {@value SampleCorpusGenerator#YAW_DELTA}, tick budget
 * {@value SampleCorpusGenerator#TICK_BUDGET}), one untimed warm-up pass, then
 * {@value #REPETITIONS} measured passes. Every returned plan is replayed from a fresh
 * start and must land; a non-solved or non-landing plan aborts instead of writing timing
 * data.
 *
 * <p>The result records the per-plan median across passes, the overall median, the
 * maximum and p95, and the proposed per-jump wall-clock budget
 * {@code min(10 x median, 2000 ms)} rounded up to 10 ms. That budget is a product
 * decision for Gate 2, not a physics constant.
 *
 * <p>Test-source {@code main}: run it with the test classes on the classpath (see
 * {@code docs/STATUS.md}). It writes only {@link #RESULT_PATH}.
 */
public final class SampleBench {

    public static final String RESULT_PATH = "data/replay/sample_01_timing.json";
    public static final int REPETITIONS = 3;
    public static final int WARMUP_PASSES = 1;
    public static final double BUDGET_MULTIPLIER = 10.0;
    public static final double BUDGET_CAP_MS = 2000.0;

    private SampleBench() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 0) {
            throw new IllegalArgumentException("usage: SampleBench");
        }
        File root = repoRoot();
        List<String> lines = ReplayScenarios.lines(root);
        if (lines.size() != ReplayScenarios.EXPECTED_COUNT) {
            throw new IllegalStateException("corpus must have "
                    + ReplayScenarios.EXPECTED_COUNT + " lines, got " + lines.size());
        }
        Scenario[] scenarios = new Scenario[lines.size()];
        CollisionWorld[] worlds = new CollisionWorld[lines.size()];
        JumpTarget[] targets = new JumpTarget[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            scenarios[i] = ScenarioParser.parse(lines.get(i));
            worlds[i] = CollisionWorld.of(SampleCorpusGenerator.blockData(), scenarios[i]);
            targets[i] = SampleReplayVerifier.targetOf(scenarios[i]);
        }

        for (int pass = 0; pass < WARMUP_PASSES; pass++) {
            for (int i = 0; i < scenarios.length; i++) {
                solveAndCheck(scenarios[i], worlds[i], targets[i]);
            }
        }

        double[][] measured = new double[scenarios.length][REPETITIONS];
        for (int pass = 0; pass < REPETITIONS; pass++) {
            for (int i = 0; i < scenarios.length; i++) {
                long start = System.nanoTime();
                SolveResult result = solve(scenarios[i], worlds[i]);
                measured[i][pass] = (System.nanoTime() - start) / 1_000_000.0;
                checkResult(scenarios[i], worlds[i], targets[i], result);
            }
        }

        double[] perPlanMedian = new double[scenarios.length];
        double totalMs = 0.0;
        for (int i = 0; i < scenarios.length; i++) {
            perPlanMedian[i] = median(measured[i]);
            totalMs += perPlanMedian[i];
        }
        double[] sorted = perPlanMedian.clone();
        Arrays.sort(sorted);
        double medianMs = median(sorted);
        double maxMs = sorted[sorted.length - 1];
        double p95Ms = sorted[(int) Math.ceil(0.95 * sorted.length) - 1];
        double proposedBudgetMs = Math.ceil(
                Math.min(BUDGET_MULTIPLIER * medianMs, BUDGET_CAP_MS) / 10.0) * 10.0;

        String json = json(root, lines.size(), scenarios, perPlanMedian, medianMs, maxMs,
                p95Ms, totalMs, proposedBudgetMs);
        File result = new File(root, RESULT_PATH);
        File parent = result.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        Files.write(result.toPath(), json.getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
        System.out.println("wrote " + RESULT_PATH);
    }

    private static SolveResult solve(Scenario scenario, CollisionWorld world) {
        return BeamSolver.solve(SampleCorpusGenerator.start(scenario.startX),
                SampleReplayVerifier.targetOf(scenario), world,
                BeamSearchConfig.of(scenario.yaw, SampleCorpusGenerator.YAW_DELTA,
                        SampleCorpusGenerator.BEAM_WIDTH,
                        SampleCorpusGenerator.TICK_BUDGET));
    }

    private static void solveAndCheck(Scenario scenario, CollisionWorld world,
            JumpTarget target) {
        checkResult(scenario, world, target, solve(scenario, world));
    }

    private static void checkResult(Scenario scenario, CollisionWorld world,
            JumpTarget target, SolveResult result) {
        if (!result.isSolved()) {
            throw new IllegalStateException(scenario.name + " did not solve: " + result);
        }
        JumpPlan plan = result.plan;
        MovementSim.State state = SampleCorpusGenerator.start(scenario.startX);
        for (TraceInput in : plan.inputs) {
            requireScenarioExpressible(scenario.name, in);
            MovementSim.step(state, in, world);
        }
        if (!target.isReachedBy(state)) {
            throw new IllegalStateException(scenario.name + " replay did not land; final=("
                    + state.x + "," + state.y + "," + state.z + ")");
        }
    }

    private static void requireScenarioExpressible(String name, TraceInput in) {
        if (in.sneak != 0) {
            throw new IllegalStateException(name + " plan uses sneak, which the corpus"
                    + " cannot express");
        }
        float mf = (in.w != 0 ? 1.0f : 0.0f) - (in.s != 0 ? 1.0f : 0.0f);
        float ms = (in.a != 0 ? 1.0f : 0.0f) - (in.d != 0 ? 1.0f : 0.0f);
        if (Float.compare(in.mf, mf) != 0 || Float.compare(in.ms, ms) != 0) {
            throw new IllegalStateException(name + " plan axes mf=" + in.mf + " ms=" + in.ms
                    + " are not expressible by w/a/s/d");
        }
    }

    private static String json(File root, int count, Scenario[] scenarios,
            double[] perPlanMedian, double medianMs, double maxMs, double p95Ms,
            double totalMs, double proposedBudgetMs) throws IOException {
        byte[] corpus = Files.readAllBytes(
                new File(root, SampleReplayVerifier.CORPUS_PATH).toPath());
        StringBuilder out = new StringBuilder(8192);
        out.append("{\n");
        out.append("  \"corpus\": \"").append(SampleReplayVerifier.CORPUS_PATH)
                .append("\",\n");
        out.append("  \"corpusSha256\": \"").append(sha256(corpus)).append("\",\n");
        out.append("  \"config\": {\"solver\": \"BeamSolver\", \"beamWidth\": ")
                .append(SampleCorpusGenerator.BEAM_WIDTH)
                .append(", \"yawDeltaDegrees\": ")
                .append(Float.toString(SampleCorpusGenerator.YAW_DELTA))
                .append(", \"tickBudget\": ").append(SampleCorpusGenerator.TICK_BUDGET)
                .append(", \"warmupPasses\": ").append(WARMUP_PASSES)
                .append(", \"repetitions\": ").append(REPETITIONS).append("},\n");
        out.append("  \"plans\": ").append(count).append(",\n");
        out.append("  \"solved\": ").append(count).append(",\n");
        out.append("  \"medianMs\": ").append(format(medianMs)).append(",\n");
        out.append("  \"maxMs\": ").append(format(maxMs)).append(",\n");
        out.append("  \"p95Ms\": ").append(format(p95Ms)).append(",\n");
        out.append("  \"totalMedianMs\": ").append(format(totalMs)).append(",\n");
        out.append("  \"proposedBudgetMs\": ").append(format(proposedBudgetMs))
                .append(",\n");
        out.append("  \"budgetRule\": \"min(10 x medianMs, 2000) rounded up to 10 ms\",\n");
        out.append("  \"javaVersion\": \"").append(System.getProperty("java.version"))
                .append("\",\n");
        out.append("  \"javaVendor\": \"").append(System.getProperty("java.vendor"))
                .append("\",\n");
        out.append("  \"os\": \"").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append("\",\n");
        out.append("  \"arch\": \"").append(System.getProperty("os.arch")).append("\",\n");
        out.append("  \"timestamp\": \"").append(Instant.now().toString()).append("\",\n");
        out.append("  \"perPlan\": [\n");
        for (int i = 0; i < count; i++) {
            out.append("    {\"name\": \"").append(scenarios[i].name)
                    .append("\", \"medianMs\": ").append(format(perPlanMedian[i]))
                    .append("}");
            out.append(i + 1 < count ? ",\n" : "\n");
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return (n & 1) == 1 ? sorted[n / 2]
                : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static String sha256(byte[] bytes) {
        return SampleReplayVerifier.sha256(bytes);
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (new File(f, ScenarioLocator.ROOT_MARKER).isFile()) {
                return f;
            }
        }
        throw new IllegalStateException(
                "no " + ScenarioLocator.ROOT_MARKER + " found above " + dir);
    }
}
