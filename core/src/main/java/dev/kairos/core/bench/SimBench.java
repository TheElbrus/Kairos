package dev.kairos.core.bench;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.fuzz.FuzzScenarios;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class SimBench {

    public static final int SEGMENT_TICKS = 100;
    public static final long WARMUP_TICKS = 2_000_000L;
    public static final long MEASURED_TICKS = 10_000_000L;
    public static final int REPETITIONS = 5;

    private static volatile long blackhole;

    private SimBench() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("usage: SimBench [corpusIndex|scenarioPath]");
        }
        Setup setup = prepare(new File(System.getProperty("user.dir")),
                args.length == 0 ? null : args[0]);
        MovementSim.State work = newWorkingState(setup.template);

        blackhole = runTicks(work, setup.template, setup.inputs, setup.world, WARMUP_TICKS);

        double[] nsPerTick = new double[REPETITIONS];
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            long start = System.nanoTime();
            blackhole = runTicks(work, setup.template, setup.inputs, setup.world, MEASURED_TICKS);
            long elapsed = System.nanoTime() - start;
            nsPerTick[repetition] = (double) elapsed / (double) MEASURED_TICKS;
        }
        Arrays.sort(nsPerTick);
        double medianNsPerTick = nsPerTick[REPETITIONS / 2];
        double ticksPerSecond = 1.0e9 / medianNsPerTick;

        System.out.println(json(setup.scenario.name, medianNsPerTick, ticksPerSecond));
    }

    public static Setup prepare(File startDir, String selector) throws IOException {
        File root = repoRoot(startDir);
        Scenario scenario = selectScenario(root, startDir, selector);
        if (scenario.totalInputTicks() < SEGMENT_TICKS) {
            throw new IllegalArgumentException(scenario.name + " has "
                    + scenario.totalInputTicks() + " input ticks, need at least " + SEGMENT_TICKS);
        }
        BlockData blocks = BlockData.load(new File(root, "data/1.8.9/blocks.json"));
        CollisionWorld world = CollisionWorld.of(blocks, scenario);
        TraceInput[] inputs = new TraceInput[SEGMENT_TICKS];
        for (int tick = 0; tick < SEGMENT_TICKS; tick++) {
            ScenarioInput segment = scenario.inputAt(tick);
            if (segment == null) {
                throw new IllegalArgumentException(scenario.name + " has no input for tick " + tick);
            }
            float forward = segment.moveForward();
            float strafe = segment.moveStrafe();
            if (segment.sneak != 0) {
                forward = (float) ((double) forward * 0.3);
                strafe = (float) ((double) strafe * 0.3);
            }
            inputs[tick] = new TraceInput(segment.w, segment.a, segment.s, segment.d,
                    segment.jump, segment.sneak, segment.sprint,
                    scenario.yaw, scenario.pitch, forward, strafe);
        }
        MovementSim.State template = MovementSim.initialState(
                scenario.startX, scenario.startY, scenario.startZ,
                0.0, 0.0, 0.0, true, false, false, false);
        return new Setup(scenario, world, inputs, template);
    }

    public static MovementSim.State newWorkingState(MovementSim.State template) {
        MovementSim.State state = new MovementSim.State();
        reset(state, template);
        return state;
    }

    public static void reset(MovementSim.State target, MovementSim.State template) {
        target.x = template.x;
        target.y = template.y;
        target.z = template.z;
        target.vx = template.vx;
        target.vy = template.vy;
        target.vz = template.vz;
        target.onGround = template.onGround;
        target.sprinting = template.sprinting;
        target.colH = template.colH;
        target.colV = template.colV;
        target.minX = template.minX;
        target.maxX = template.maxX;
        target.minZ = template.minZ;
        target.maxZ = template.maxZ;
        target.maxY = template.maxY;
        target.jumpTicks = template.jumpTicks;
        target.sprintingTicksLeft = template.sprintingTicksLeft;
        target.sprintToggleTimer = template.sprintToggleTimer;
        target.moveForward = template.moveForward;
        target.prevSneak = template.prevSneak;
        target.jumpMovementFactor = template.jumpMovementFactor;
    }

    public static long runTicks(MovementSim.State work, MovementSim.State template,
            TraceInput[] inputs, CollisionWorld world, long ticks) {
        if (ticks % inputs.length != 0) {
            throw new IllegalArgumentException("ticks must be a multiple of " + inputs.length);
        }
        long blocks = ticks / inputs.length;
        long sink = 0L;
        for (long block = 0; block < blocks; block++) {
            for (int tick = 0; tick < inputs.length; tick++) {
                MovementSim.step(work, inputs[tick], world);
                sink += Double.doubleToRawLongBits(work.x);
            }
            reset(work, template);
        }
        blackhole = sink;
        return sink;
    }

    private static Scenario selectScenario(File root, File startDir, String selector)
            throws IOException {
        if (selector == null || selector.trim().isEmpty()) {
            return ScenarioParser.parse(FuzzScenarios.lines(root).get(0));
        }
        String value = selector.trim();
        try {
            int index = Integer.parseInt(value);
            List<String> corpus = FuzzScenarios.lines(root);
            if (index < 0 || index >= corpus.size()) {
                throw new IllegalArgumentException("scenario index " + index
                        + " is outside 0.." + (corpus.size() - 1));
            }
            return ScenarioParser.parse(corpus.get(index));
        } catch (NumberFormatException notAnIndex) {
            File file = new File(value);
            if (!file.isAbsolute()) {
                File base = startDir == null ? new File(".") : startDir;
                file = new File(base, value);
                if (!file.isFile()) {
                    file = new File(root, value);
                }
            }
            if (!file.isFile()) {
                throw new IOException("no scenario file at " + file.getAbsolutePath());
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return ScenarioParser.parse(text.trim());
        }
    }

    private static File repoRoot(File startDir) throws IOException {
        File start = (startDir == null ? new File(".") : startDir).getCanonicalFile();
        for (File dir = start; dir != null; dir = dir.getParentFile()) {
            if (new File(dir, ScenarioLocator.ROOT_MARKER).isFile()) {
                return dir;
            }
        }
        throw new IOException("no " + ScenarioLocator.ROOT_MARKER
                + " found above " + start.getAbsolutePath());
    }

    private static String json(String scenario, double nsPerTick, double ticksPerSecond) {
        return new StringBuilder(512)
                .append('{')
                .append("\"scenario\":").append(quote(scenario)).append(',')
                .append("\"warmupTicks\":").append(WARMUP_TICKS).append(',')
                .append("\"measuredTicks\":").append(MEASURED_TICKS).append(',')
                .append("\"repetitions\":").append(REPETITIONS).append(',')
                .append("\"ticksPerSecond\":")
                .append(String.format(Locale.ROOT, "%.3f", ticksPerSecond)).append(',')
                .append("\"nsPerTick\":")
                .append(String.format(Locale.ROOT, "%.6f", nsPerTick)).append(',')
                .append("\"javaVersion\":")
                .append(quote(System.getProperty("java.version"))).append(',')
                .append("\"javaVendor\":")
                .append(quote(System.getProperty("java.vendor"))).append(',')
                .append("\"os\":").append(quote(System.getProperty("os.name"))).append(',')
                .append("\"arch\":").append(quote(System.getProperty("os.arch"))).append(',')
                .append("\"maxMemory\":").append(Runtime.getRuntime().maxMemory()).append(',')
                .append("\"timestamp\":")
                .append(quote(java.time.Instant.now().toString()))
                .append('}')
                .toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static final class Setup {

        public final Scenario scenario;
        public final CollisionWorld world;
        public final TraceInput[] inputs;
        public final MovementSim.State template;

        Setup(Scenario scenario, CollisionWorld world, TraceInput[] inputs,
                MovementSim.State template) {
            this.scenario = scenario;
            this.world = world;
            this.inputs = inputs;
            this.template = template;
        }
    }
}
