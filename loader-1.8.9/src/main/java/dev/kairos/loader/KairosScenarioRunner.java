package dev.kairos.loader;

// Every Minecraft API used here is cited from the decompiled source in mc-src/ (MCP
// `stable_22`), as AGENTS.md requires. mc-src/ is gitignored and never committed.

import dev.kairos.core.fuzz.FuzzScenarios;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;

import net.minecraft.block.Block; // mc-src/net/minecraft/block/Block.java
import net.minecraft.block.properties.IProperty; // mc-src/net/minecraft/block/properties/IProperty.java
import net.minecraft.block.state.IBlockState; // mc-src/net/minecraft/block/state/IBlockState.java
import net.minecraft.client.Minecraft; // mc-src/net/minecraft/client/Minecraft.java
import net.minecraft.client.entity.EntityPlayerSP; // mc-src/net/minecraft/client/entity/EntityPlayerSP.java
import net.minecraft.client.settings.KeyBinding; // mc-src/net/minecraft/client/settings/KeyBinding.java
import net.minecraft.command.ICommandSender; // mc-src/net/minecraft/command/ICommandSender.java
import net.minecraft.entity.player.EntityPlayerMP; // mc-src/net/minecraft/entity/player/EntityPlayerMP.java
import net.minecraft.item.ItemStack; // mc-src/net/minecraft/item/ItemStack.java
import net.minecraft.network.play.server.S23PacketBlockChange; // mc-src/net/minecraft/network/play/server/S23PacketBlockChange.java
import net.minecraft.server.MinecraftServer; // mc-src/net/minecraft/server/MinecraftServer.java
import net.minecraft.util.BlockPos; // mc-src/net/minecraft/util/BlockPos.java
import net.minecraft.util.ChatComponentText; // mc-src/net/minecraft/util/ChatComponentText.java
import net.minecraft.util.EnumChatFormatting; // mc-src/net/minecraft/util/EnumChatFormatting.java
import net.minecraft.util.MovementInput; // mc-src/net/minecraft/util/MovementInput.java
import net.minecraft.world.EnumDifficulty; // mc-src/net/minecraft/world/EnumDifficulty.java
import net.minecraft.world.WorldServer; // mc-src/net/minecraft/world/WorldServer.java
import net.minecraftforge.event.world.WorldEvent; // mc-src/net/minecraftforge/event/world/WorldEvent.java
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent; // mc-src/net/minecraftforge/fml/common/gameevent/TickEvent.java

import org.lwjgl.input.Keyboard; // LWJGL 2 constants (library, not mc-src)

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Runs one scenario (PLAN.md Phase 0 task 0.5): builds the blocks on the integrated
 * server, places the player, waits for it to settle, feeds scripted inputs tick by tick,
 * and records a trace in the ADR 0010 format.
 *
 * <p>Event order within one tick, which is what makes a run well defined:
 * <ol>
 *   <li>{@code ClientTickEvent.START}. This class runs FIRST
 *       ({@link EventPriority#HIGHEST}) and writes the movement axes / yaw / sprint key
 *       for the upcoming tick, and calls {@code recorder.start(...)} on the tick that
 *       begins recording.</li>
 *   <li>{@code KairosRecorder} runs at the default priority and, on that same START,
 *       writes the {@code tick:-1} anchor with {@code te = 0}.</li>
 *   <li>{@code Minecraft.runTick} runs the physics.</li>
 *   <li>{@code ClientTickEvent.END}. The recorder runs first (default) and writes the
 *       tick's line; this class runs LAST ({@link EventPriority#LOWEST}) and advances the
 *       tick counter, so it always sees a line that is already on disk.</li>
 * </ol>
 *
 * <p>Isolation while a run is active: the real {@code MovementInput} is replaced by
 * {@link ScenarioMovementInput} and {@code GameSettings.keyBindSprint} by
 * {@link ScriptedSprintKey}, so neither the physical keyboard nor the mouse can leak
 * into the physics or the trace. The key bind swap matters because
 * {@code Minecraft.runTick}'s keyboard loop re-sets binding states from physical events
 * between this class's START handler and the physics ({@code Minecraft.java:1561-1563}),
 * which silently clobbered a plain {@code setKeyBindState} call during runmany testing.
 * Both hooks are restored when the run ends.
 */
public final class KairosScenarioRunner {

    /**
     * Steady ground state: {@code Block.onLanded} zeroes motionY (Block.java:1125-1126),
     * then each tick gravity is {@code motionY -= 0.08} (EntityLivingBase.java:1283) and
     * drag is {@code motionY *= (double) 0.98f} (EntityLivingBase.java:1284), so the
     * landing tick's end state is {@code (0 - 0.08) * (double) 0.98f}.
     */
    static final double GROUND_VEL_Y = -0.08 * (double) 0.98f;

    /** Ticks we are willing to wait for the server and client to agree before aborting. */
    private static final int MAX_WAIT_TICKS = 200;

    /** How close the client position must be to the requested one, in blocks. */
    private static final double POS_EPSILON = 1.0E-6;

    /** Fuzz records a single trace per corpus line; progress prints every N scenarios. */
    private static final int FUZZ_PROGRESS_EVERY = 25;

    private enum Phase {
        IDLE, TELEPORT, BUILD, TELEPORT2, SETTLE, RECORD
    }

    private final KairosRecorder recorder;

    private Phase phase = Phase.IDLE;
    private Scenario scenario;
    private IBlockState[] regionStates;

    /**
     * World states under the scenario regions before this run replaced them, captured on
     * the first build ({@code savedCoords} holds x,y,z triples parallel to the states).
     * Restored when the run ends so runs cannot contaminate the next recording; written
     * on the server thread inside the build task, read on the client thread at restore.
     */
    private volatile IBlockState[] savedStates;
    private volatile int[] savedCoords;
    private volatile int savedDim;

    /** Bumped per run; a queued build from a dead run must not place blocks. */
    private volatile int runGeneration;
    private String traceName;
    private int runsTotal;
    private int runIndex;
    private int waitTicks;
    private int settleCounter;
    private int recordTick;
    private double lastSettleX;
    private double lastSettleY;
    private double lastSettleZ;
    private boolean haveLastSettlePos;
    private File[] runFiles;
    private MovementInput originalMovementInput;
    private ScenarioMovementInput scriptedInput;
    private KeyBinding originalSprintKey;
    private final ScriptedSprintKey scriptedSprintKey = new ScriptedSprintKey();

    /**
     * Fuzz batch state (task 1.7.1). The corpus is read and every scenario/state is
     * resolved before the first world edit; {@code fuzzNext} is the 0-based index of the
     * scenario being (or about to be) recorded, {@code fuzzTotal} the batch size.
     */
    private boolean fuzzMode;
    private List<Scenario> fuzzScenarios;
    private IBlockState[][] fuzzStates;
    private int fuzzNext;
    private int fuzzTotal;

    public KairosScenarioRunner(KairosRecorder recorder) {
        this.recorder = recorder;
    }

    public boolean isBusy() {
        return this.phase != Phase.IDLE;
    }

    // --- command entry point --------------------------------------------------------

    /** {@code /kairos scenario run <name>} (runs = 1) or {@code runmany <name> <n>}. */
    public void run(String name, int runs, ICommandSender sender) {
        if (isBusy()) {
            chat(sender, "a run is already in progress");
            return;
        }
        if (runs < 1) {
            runs = 1;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || mc.getIntegratedServer() == null) {
            chatRed(sender, "scenario needs a loaded singleplayer world");
            return;
        }
        Scenario parsed;
        try {
            parsed = ScenarioParser.parse(readScenarioFile(mc, name));
        } catch (IOException e) {
            chatRed(sender, "could not read scenario '" + name + "': " + e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            chatRed(sender, "scenario '" + name + "' is invalid: " + e.getMessage());
            return;
        }
        IBlockState[] states;
        try {
            states = resolveRegionStates(parsed);
        } catch (IllegalArgumentException e) {
            chatRed(sender, "scenario '" + name + "' is invalid: " + e.getMessage());
            return;
        }

        if (runs > 1) {
            File dir = tracesDir(mc);
            // runmany is a determinism check, so its own previous outputs are discarded to
            // let it be repeated. Only files of the form <name>_runNN.jsonl are touched:
            // a single <name>.jsonl run and every other scenario are left alone, and the
            // recorder still refuses to overwrite anything it was not told about.
            deleteOldRunFiles(dir, name);
            this.runFiles = new File[runs];
            for (int i = 0; i < runs; i++) {
                this.runFiles[i] = new File(dir, runFileName(name, i));
            }
        } else {
            this.runFiles = null;
        }

        this.scenario = parsed;
        this.regionStates = states;
        this.runsTotal = runs;
        this.runIndex = 0;
        beginRun(sender);
    }

    /**
     * {@code /kairos scenario fuzz <count> [resume]} (task 1.7.1): records the first
     * {@code count} committed corpus scenarios, one trace named exactly like the scenario
     * ({@code fuzz_NNNN.jsonl}), sequentially, with the same build/teleport/settle/record
     * lifecycle as {@link #run(String, int, ICommandSender)}.
     *
     * <p>All parsing and state resolution happens here, before any world edit: a bad
     * corpus line fails with its scenario name and no block is placed. Without
     * {@code resume} an existing expected trace refuses the whole batch; with
     * {@code resume} a leading run of existing traces is skipped and recording continues
     * at the first missing one (a trace after a gap is refused, never overwritten).
     */
    public void runFuzz(int count, boolean resume, ICommandSender sender) {
        if (isBusy()) {
            chat(sender, "a run is already in progress");
            return;
        }
        if (count < 1) {
            chatRed(sender, "fuzz count must be >= 1");
            return;
        }
        if (this.recorder.isRecording()) {
            chatRed(sender, "a manual recording is active; /kairos record stop it first");
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || mc.getIntegratedServer() == null) {
            chatRed(sender, "fuzz needs a loaded singleplayer world");
            return;
        }

        List<String> lines;
        try {
            lines = FuzzScenarios.lines(mc.mcDataDir);
        } catch (IOException e) {
            chatRed(sender, "fuzz corpus unavailable: " + e.getMessage());
            return;
        }
        int total = Math.min(count, lines.size());

        Scenario[] scenarios = new Scenario[total];
        IBlockState[][] states = new IBlockState[total][];
        String[] names = new String[total];
        for (int i = 0; i < total; i++) {
            try {
                Scenario parsed = ScenarioParser.parse(lines.get(i));
                states[i] = resolveRegionStates(parsed);
                scenarios[i] = parsed;
                names[i] = parsed.name;
            } catch (IllegalArgumentException e) {
                chatRed(sender, "fuzz scenario " + (i + 1) + "/" + total + " in "
                        + FuzzScenarios.RELATIVE_PATH + " is invalid: " + e.getMessage());
                return;
            }
        }

        File dir = tracesDir(mc);
        int firstExisting = -1;
        for (int i = 0; i < total; i++) {
            if (new File(dir, names[i] + ".jsonl").isFile()) {
                firstExisting = i;
                break;
            }
        }
        if (!resume && firstExisting >= 0) {
            chatRed(sender, "fuzz refused: " + new File(dir, names[firstExisting] + ".jsonl").getPath()
                    + " already exists; delete it or rerun with 'resume'");
            return;
        }
        int skipped = 0;
        if (resume) {
            while (skipped < total && new File(dir, names[skipped] + ".jsonl").isFile()) {
                skipped++;
            }
            if (skipped == total) {
                chat(sender, "all " + total + " fuzz traces already exist; nothing to record");
                return;
            }
            for (int i = skipped; i < total; i++) {
                if (new File(dir, names[i] + ".jsonl").isFile()) {
                    chatRed(sender, "fuzz resume refused: "
                            + new File(dir, names[i] + ".jsonl").getPath()
                            + " exists after the first missing trace");
                    return;
                }
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            chatRed(sender, "fuzz could not create " + dir.getPath());
            return;
        }

        this.fuzzMode = true;
        this.fuzzScenarios = Arrays.asList(scenarios);
        this.fuzzStates = states;
        this.fuzzNext = skipped;
        this.fuzzTotal = total;
        this.runsTotal = 0;
        this.runIndex = 0;

        chat(sender, "fuzz: " + total + " scenarios from " + FuzzScenarios.RELATIVE_PATH
                + (resume ? " (resume)" : ""));
        if (skipped > 0) {
            chat(sender, "fuzz resume: skipping " + skipped + " existing trace(s)");
        }
        loadFuzzScenario();
        beginRun(sender);
    }

    /** Makes the scenario at {@code fuzzNext} the current one (states already resolved). */
    private void loadFuzzScenario() {
        this.scenario = this.fuzzScenarios.get(this.fuzzNext);
        this.regionStates = this.fuzzStates[this.fuzzNext];
    }

    private void beginRun(ICommandSender sender) {
        this.runGeneration++;
        this.savedStates = null;
        this.savedCoords = null;
        this.traceName = !this.fuzzMode && this.runsTotal > 1
                ? runFileBase(this.scenario.name, this.runIndex) : this.scenario.name;
        this.waitTicks = 0;
        this.settleCounter = 0;
        this.recordTick = 0;
        this.haveLastSettlePos = false;

        Minecraft mc = Minecraft.getMinecraft();
        this.originalMovementInput = mc.thePlayer.movementInput;
        this.scriptedInput = new ScenarioMovementInput();
        this.scriptedInput.clear();
        mc.thePlayer.movementInput = this.scriptedInput;
        // GameSettings.keyBindSprint is a plain public field (GameSettings.java:145), so
        // swapping it isolates both the physics and the recorder from physical events.
        this.originalSprintKey = mc.gameSettings.keyBindSprint;
        mc.gameSettings.keyBindSprint = this.scriptedSprintKey;

        controlWorld(mc);
        resetPlayerState(mc);
        teleportToStart(mc);
        this.phase = Phase.TELEPORT;
        if (!this.fuzzMode) {
            chat(sender, (this.runsTotal > 1 ? ("run " + (this.runIndex + 1) + "/" + this.runsTotal + ": ") : "")
                    + "placing the player for '" + this.scenario.name + "'");
        }
    }

    // --- tick handlers --------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || this.phase == Phase.IDLE) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            abort("world went away");
            return;
        }

        switch (this.phase) {
            case TELEPORT:
                if (atTarget(player)) {
                    buildBlocks(mc);
                    this.phase = Phase.BUILD;
                    this.waitTicks = 0;
                } else if (timedOut()) {
                    abort("client never reached the start position (" + fmt(player.posX) + ","
                            + fmt(player.posY) + "," + fmt(player.posZ) + ")");
                    return;
                }
                break;
            case BUILD:
                if (clientHasBlocks(mc)) {
                    teleportToStart(mc);
                    this.phase = Phase.TELEPORT2;
                    this.waitTicks = 0;
                } else if (timedOut()) {
                    abort("client world does not show every scenario block: "
                            + firstBlockMismatch(mc));
                    return;
                } else if (this.waitTicks % 20 == 0) {
                    // Two failure modes while chunks settle: a block placed into a
                    // not-yet-populated chunk gets no update packet (World.java:381), and
                    // re-setting an unchanged state sends nothing either (Chunk
                    // .setBlockState returns null -> World.setBlockState returns early),
                    // so rebuild AND resend the current states directly.
                    buildBlocks(mc);
                    resendBlocks(mc);
                }
                break;
            case TELEPORT2:
                if (atTarget(player) && player.motionX == 0.0 && player.motionY == 0.0
                        && player.motionZ == 0.0) {
                    resetPlayerState(mc);
                    this.phase = Phase.SETTLE;
                    this.settleCounter = 0;
                } else if (timedOut()) {
                    abort("client did not settle after the second teleport");
                    return;
                }
                break;
            case SETTLE:
                // No scripted input while the player lands and its motion decays.
                this.scriptedInput.clear();
                setSprintKey(false);
                applyYaw(player);
                break;
            case RECORD:
                ScenarioInput segment = this.scenario.inputAt(this.recordTick);
                if (segment == null) {
                    abort("ran out of scripted input at tick " + this.recordTick);
                    return;
                }
                this.scriptedInput.apply(segment);
                setSprintKey(segment.sprint != 0);
                applyYaw(player);
                if (this.recordTick == 0) {
                    // Start the recorder on the same START, so the anchor and tick 0 share
                    // this tick. This handler runs at HIGHEST priority (see the class
                    // comment), so recorder.start() is called before KairosRecorder's own
                    // START handler writes the tick:-1 anchor later in this dispatch.
                    // traceName carries the run index for runmany and is the scenario name
                    // otherwise (single run and fuzz); every run stamps the SAME base
                    // scenario label, so the scenario field never differs (ADR 0010).
                    this.recorder.start(this.traceName, this.scenario.name, null);
                    if (!this.recorder.isRecording()) {
                        // start() refused (bad name or the file already exists): do not run
                        // a scenario whose bytes would go nowhere.
                        abort("recorder refused to start '" + this.traceName + "'");
                        return;
                    }
                }
                break;
            default:
                break;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || this.phase == Phase.IDLE) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            abort("world went away");
            return;
        }

        if (this.phase == Phase.SETTLE) {
            this.settleCounter++;
            if (this.settleCounter >= this.scenario.settleTicks) {
                String problem = settleProblem(player);
                if (problem != null) {
                    abort("settle check failed: " + problem);
                    return;
                }
                this.phase = Phase.RECORD;
                this.recordTick = 0;
            }
            rememberSettlePos(player);
            return;
        }

        if (this.phase == Phase.RECORD) {
            // KairosRecorder (default priority) already wrote this tick's line.
            this.recordTick++;
            if (this.recordTick >= this.scenario.totalInputTicks()) {
                this.recorder.stop(null);
                finishRun();
            } else {
                rememberSettlePos(player);   // keeps the last-2-ticks check armed
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (this.phase != Phase.IDLE) {
            abort("world unloaded during a run");
        }
    }

    // --- run lifecycle --------------------------------------------------------------

    private void finishRun() {
        Minecraft mc = Minecraft.getMinecraft();
        restoreMovementInput(mc);
        scheduleRestore(mc);

        if (this.fuzzMode) {
            this.fuzzNext++;
            if (this.fuzzNext < this.fuzzTotal) {
                if (this.fuzzNext % FUZZ_PROGRESS_EVERY == 0) {
                    chat(null, "fuzz progress: " + this.fuzzNext + "/" + this.fuzzTotal
                            + " traces");
                }
                loadFuzzScenario();
                beginRun(null);
                return;
            }
            chat(null, "fuzz progress: " + this.fuzzNext + "/" + this.fuzzTotal + " traces");
            chat(null, "fuzz done: " + this.fuzzTotal + "/" + this.fuzzTotal + " traces -> "
                    + tracesDir(mc).getPath());
            this.phase = Phase.IDLE;
            clearFuzz();
            return;
        }

        chat(null, "run " + (this.runIndex + 1) + "/" + this.runsTotal + " done: "
                + this.scenario.totalInputTicks() + " ticks -> " + this.traceName + ".jsonl");

        this.runIndex++;
        if (this.runIndex < this.runsTotal) {
            beginRun(null);
            return;
        }

        this.phase = Phase.IDLE;
        if (this.runsTotal > 1) {
            reportDeterminism();
        }
    }

    private void reportDeterminism() {
        try {
            byte[] first = Files.readAllBytes(this.runFiles[0].toPath());
            for (int i = 1; i < this.runFiles.length; i++) {
                byte[] other = Files.readAllBytes(this.runFiles[i].toPath());
                if (!Arrays.equals(first, other)) {
                    int line = firstDifferingLine(first, other);
                    chatRed(null, "NOT byte-identical: run 1 vs run " + (i + 1)
                            + " differ at line " + line + " (1-based). Investigate; do not "
                            + "work around it.");
                    return;
                }
            }
            chat(null, "all " + this.runsTotal + " runs are byte-identical for '"
                    + this.scenario.name + "'.");
        } catch (IOException e) {
            chatRed(null, "could not read back the traces: " + e.getMessage());
        }
    }

    private void abort(String why) {
        Minecraft mc = Minecraft.getMinecraft();
        if (this.recorder.isRecording()) {
            this.recorder.stop(null);
        }
        restoreMovementInput(mc);
        scheduleRestore(mc);
        if (this.fuzzMode) {
            chatRed(null, "fuzz aborted at " + this.scenario.name + " (" + (this.fuzzNext + 1)
                    + "/" + this.fuzzTotal + "): " + why);
        } else {
            chatRed(null, "scenario aborted: " + why);
        }
        this.phase = Phase.IDLE;
        clearFuzz();
    }

    /** Drops the fuzz batch bookkeeping; a completed or aborted batch leaves no mode on. */
    private void clearFuzz() {
        this.fuzzMode = false;
        this.fuzzScenarios = null;
        this.fuzzStates = null;
        this.fuzzNext = 0;
        this.fuzzTotal = 0;
    }

    private boolean timedOut() {
        return ++this.waitTicks > MAX_WAIT_TICKS;
    }

    // --- server-side world edits ----------------------------------------------------

    /**
     * Determinism controls, applied on the SERVER thread. {@code doMobSpawning} false and
     * Peaceful stop new hostiles (the spawner call is gated at WorldServer.java:208);
     * {@code doDaylightCycle} false plus a fixed time stop the day moving; rain and
     * thunder are cleared.
     */
    private void controlWorld(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();        // Minecraft.java:2303
        final int dim = mc.theWorld.provider.getDimensionId();          // World.java:143, WorldProvider.java:244
        server.addScheduledTask(new Runnable() {                        // MinecraftServer.java:1297
            @Override
            public void run() {
                WorldServer world = server.worldServerForDimension(dim);   // MinecraftServer.java:760
                world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");   // GameRules.java:36
                world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                world.getWorldInfo().setDifficulty(EnumDifficulty.PEACEFUL);          // WorldInfo.java:709
                world.getWorldInfo().setWorldTime(6000L);                             // WorldInfo.java:395
                world.getWorldInfo().setRaining(false);                               // WorldInfo.java:484
                world.getWorldInfo().setThundering(false);                            // WorldInfo.java:456
                world.getWorldInfo().setRainTime(Integer.MAX_VALUE);                  // WorldInfo.java:498
                world.getWorldInfo().setThunderTime(Integer.MAX_VALUE);               // WorldInfo.java:470
            }
        });
    }

    /** Places the player on the server side; the S08 packet moves the client. */
    private void teleportToStart(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        final EntityPlayerMP serverPlayer = server.getConfigurationManager()
                .getPlayerByUsername(mc.thePlayer.getName());       // MinecraftServer.java:1125, ServerConfigurationManager.java:642
        if (serverPlayer == null) {
            abort("no server-side player entity");
            return;
        }
        final double x = this.scenario.startX;
        final double y = this.scenario.startY;
        final double z = this.scenario.startZ;
        final float yaw = this.scenario.yaw;
        final float pitch = this.scenario.pitch;
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                // NetHandlerPlayServer.setPlayerLocation sets the server entity AND sends
                // S08PacketPlayerPosLook (NetHandlerPlayServer.java:373, :377, send at
                // :400); the client handler zeroes motion and calls setPositionAndRotation
                // (NetHandlerPlayClient.java:528-557).
                serverPlayer.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
            }
        });
        this.waitTicks = 0;
    }

    /**
     * Resolves every scenario region's block state once, on the command's thread, before
     * any world is touched: an unknown id, a property name the block does not have or a
     * value that is not an allowed value must fail with a clear message instead of
     * building half a scenario. The scenario {@code props} are applied onto the block's
     * default state ({@code IBlockState.withProperty}, IBlockState.java:25); the value
     * strings are the exact names {@code data/1.8.9/blocks.json} uses
     * ({@code IProperty.getName(T)}, IProperty.java:21, the same function
     * {@code BlockDumper.valueName} wrote with, BlockDumper.java:334-336).
     */
    private static IBlockState[] resolveRegionStates(Scenario scenario) {
        IBlockState[] states = new IBlockState[scenario.blocks.size()];
        for (int i = 0; i < states.length; i++) {
            ScenarioBlock region = scenario.blocks.get(i);
            Block block = Block.getBlockFromName(normalizeId(region.id));   // Block.java:366
            if (block == null) {
                throw new IllegalArgumentException("blocks[" + i + "].id: unknown block \""
                        + region.id + "\"");
            }
            IBlockState state = block.getDefaultState();
            for (Map.Entry<String, String> prop : region.props.entrySet()) {
                state = applyProp(state, prop.getKey(), prop.getValue(), i);
            }
            states[i] = state;
        }
        return states;
    }

    /** One {@code props} entry applied by value name, or {@link IllegalArgumentException}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IBlockState applyProp(IBlockState state, String name, String value,
            int index) {
        IProperty property = null;
        for (IProperty candidate : state.getPropertyNames()) {      // IBlockState.java:15
            if (candidate.getName().equals(name)) {
                property = candidate;
                break;
            }
        }
        if (property == null) {
            throw new IllegalArgumentException("blocks[" + index + "].props." + name
                    + ": block \"" + Block.blockRegistry.getNameForObject(state.getBlock())
                    + "\" has no such property");
        }
        for (Object allowed : property.getAllowedValues()) {        // IProperty.java:11
            if (property.getName((Comparable) allowed).equals(value)) {  // IProperty.java:21
                return state.withProperty(property, (Comparable) allowed);  // IBlockState.java:25
            }
        }
        throw new IllegalArgumentException("blocks[" + index + "].props." + name + ": \""
                + value + "\" is not a value of this property");
    }

    /** Fills every scenario region, flag 2 (sync to clients, no neighbour update). */
    private void buildBlocks(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        final int dim = mc.theWorld.provider.getDimensionId();
        this.savedDim = dim;
        final ScenarioBlock[] blocks =
                this.scenario.blocks.toArray(new ScenarioBlock[this.scenario.blocks.size()]);
        final IBlockState[] states = this.regionStates;
        final boolean capture = this.savedStates == null;
        final int volume = capture ? regionVolume(blocks) : 0;
        final int generation = this.runGeneration;
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (generation != runGeneration) {
                    return;   // the run that asked for this build is gone
                }
                WorldServer world = server.worldServerForDimension(dim);
                IBlockState[] olds = capture ? new IBlockState[volume] : null;
                int[] coords = capture ? new int[volume * 3] : null;
                int n = 0;
                for (int r = 0; r < blocks.length; r++) {
                    ScenarioBlock region = blocks[r];
                    IBlockState state = states[r];
                    int stepX = region.toX >= region.fromX ? 1 : -1;
                    int stepY = region.toY >= region.fromY ? 1 : -1;
                    int stepZ = region.toZ >= region.fromZ ? 1 : -1;
                    for (int x = region.fromX; ; x += stepX) {
                        for (int y = region.fromY; ; y += stepY) {
                            for (int z = region.fromZ; ; z += stepZ) {
                                if (capture) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    olds[n] = world.getBlockState(pos);
                                    coords[n * 3] = x;
                                    coords[n * 3 + 1] = y;
                                    coords[n * 3 + 2] = z;
                                    n++;
                                }
                                // flags 2 = send to clients, no neighbour update, exactly
                                // like /setblock (CommandSetBlock.java:90).
                                world.setBlockState(new BlockPos(x, y, z), state, 2);
                                if (z == region.toZ) break;
                            }
                            if (y == region.toY) break;
                        }
                        if (x == region.toX) break;
                    }
                }
                if (capture) {
                    savedStates = olds;
                    savedCoords = coords;
                }
            }
        });
    }

    /**
     * Sends the CURRENT server state of every scenario position as S23 block-change
     * packets. Needed because setting an unchanged state sends nothing: repairing a
     * position whose first update was suppressed requires an explicit packet.
     */
    private void resendBlocks(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        final String playerName = mc.thePlayer.getName();
        final int dim = mc.theWorld.provider.getDimensionId();
        final ScenarioBlock[] blocks =
                this.scenario.blocks.toArray(new ScenarioBlock[this.scenario.blocks.size()]);
        final int generation = this.runGeneration;
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (generation != runGeneration) {
                    return;
                }
                WorldServer world = server.worldServerForDimension(dim);
                EntityPlayerMP serverPlayer = server.getConfigurationManager()
                        .getPlayerByUsername(playerName);
                if (world == null || serverPlayer == null) {
                    return;
                }
                for (ScenarioBlock region : blocks) {
                    int stepX = region.toX >= region.fromX ? 1 : -1;
                    int stepY = region.toY >= region.fromY ? 1 : -1;
                    int stepZ = region.toZ >= region.fromZ ? 1 : -1;
                    for (int x = region.fromX; ; x += stepX) {
                        for (int y = region.fromY; ; y += stepY) {
                            for (int z = region.fromZ; ; z += stepZ) {
                                serverPlayer.playerNetServerHandler.sendPacket(
                                        new S23PacketBlockChange(world, new BlockPos(x, y, z)));
                                if (z == region.toZ) break;
                            }
                            if (y == region.toY) break;
                        }
                        if (x == region.toX) break;
                    }
                }
            }
        });
    }

    /** @return total number of positions covered by the scenario regions. */
    private static int regionVolume(ScenarioBlock[] blocks) {
        int total = 0;
        for (ScenarioBlock region : blocks) {
            total += (Math.abs(region.toX - region.fromX) + 1)
                    * (Math.abs(region.toY - region.fromY) + 1)
                    * (Math.abs(region.toZ - region.fromZ) + 1);
        }
        return total;
    }

    /**
     * Schedules the pre-run world to be put back on the server thread. Called at the end
     * of every run (also on abort) so leftover blocks can never leak into the next
     * recording; no-op before the first build or when the world is already gone.
     */
    private void scheduleRestore(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        final IBlockState[] olds = this.savedStates;
        final int[] coords = this.savedCoords;
        final int dim = this.savedDim;
        this.savedStates = null;
        this.savedCoords = null;
        if (olds == null || server == null || mc.theWorld == null) {
            return;
        }
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                WorldServer world = server.worldServerForDimension(dim);
                if (world == null) {
                    return;
                }
                // Flag 2 like the build: the client must see the rollback too.
                for (int i = 0; i < olds.length; i++) {
                    world.setBlockState(new BlockPos(coords[i * 3], coords[i * 3 + 1],
                            coords[i * 3 + 2]), olds[i], 2);
                }
            }
        });
    }

    /**
     * @return the first region position whose client state differs, with the expected
     *         (requested and stored form), the client's and the server's view plus the
     *         raw state-ID round trip.
     */
    private String firstBlockMismatch(Minecraft mc) {
        WorldServer serverWorld = mc.getIntegratedServer() == null ? null
                : mc.getIntegratedServer().worldServerForDimension(
                        mc.theWorld.provider.getDimensionId());
        for (int r = 0; r < this.scenario.blocks.size(); r++) {
            ScenarioBlock region = this.scenario.blocks.get(r);
            IBlockState requested = this.regionStates[r];
            IBlockState expected = storedState(requested);
            int stepX = region.toX >= region.fromX ? 1 : -1;
            int stepY = region.toY >= region.fromY ? 1 : -1;
            int stepZ = region.toZ >= region.fromZ ? 1 : -1;
            for (int x = region.fromX; ; x += stepX) {
                for (int y = region.fromY; ; y += stepY) {
                    for (int z = region.fromZ; ; z += stepZ) {
                        BlockPos pos = new BlockPos(x, y, z);
                        IBlockState actual = mc.theWorld.getBlockState(pos);
                        if (actual != expected) {
                            IBlockState server = serverWorld == null ? null
                                    : serverWorld.getBlockState(pos);
                            Integer id = Block.BLOCK_STATE_IDS.get(requested);
                            String idText = id == null ? "not-in-state-ids"
                                    : id + "->" + describeState(
                                            Block.BLOCK_STATE_IDS.getByValue(id));
                            return "(" + x + "," + y + "," + z + ") requested "
                                    + describeState(requested)
                                    + (requested == expected ? "" : " stored " + describeState(expected))
                                    + " [" + idText + "] client "
                                    + describeState(actual) + " server "
                                    + (server == null ? "?" : describeState(server))
                                    + (server == expected ? " [server ok]" : " [server differs]");
                        }
                        if (z == region.toZ) break;
                    }
                    if (y == region.toY) break;
                }
                if (x == region.toX) break;
            }
        }
        return "none (raced between ticks - report this)";
    }

    /** @return compact full state, e.g. {@code minecraft:oak_stairs{facing=south, ...}}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String describeState(IBlockState state) {
        StringBuilder out = new StringBuilder();
        out.append(Block.blockRegistry.getNameForObject(state.getBlock())).append('{');
        boolean first = true;
        for (Map.Entry entry : ((Map<IProperty, Comparable>) state.getProperties()).entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            IProperty property = (IProperty) entry.getKey();
            out.append(property.getName()).append('=')
                    .append(property.getName((Comparable) entry.getValue()));
        }
        return out.append('}').toString();
    }

    /**
     * The world-storage form of a state. Chunks store 16-bit state ids, not state objects:
     * {@code Block.registerBlock} packs every valid state as {@code blockId << 4 | meta}
     * (Block.java:1934) and {@code ObjectIntIdentityMap.getByValue} returns the LAST state
     * put on an id. BlockStairs.getMetaFromState (BlockStairs.java:537-543) ignores SHAPE,
     * so all five shapes of one facing/half share an id and the world reads back one
     * canonical shape. What the game simulates is unaffected: stair collision recomputes
     * the shape from neighbours (addCollisionBoxesToList, BlockStairs.java:384-391), so
     * placement checks must compare against this stored form or stairs never match.
     */
    private static IBlockState storedState(IBlockState state) {
        int id = Block.BLOCK_STATE_IDS.get(state);          // -1 when unknown
        if (id < 0) {
            return state;
        }
        IBlockState stored = Block.BLOCK_STATE_IDS.getByValue(id);
        return stored == null ? state : stored;
    }

    /** @return true when the CLIENT world shows every scenario block (physics runs there). */
    private boolean clientHasBlocks(Minecraft mc) {
        // The states are registry singletons: withProperty returns the container's table
        // entry (BlockState.StateImplementation, BlockState.java:141-148) and equals is
        // identity (:161-163), so reference equality IS full-state equality - a block
        // with the right id but a wrong property value fails the check. Compare against
        // the STORED form: a chunk keeps a 16-bit state id, not a state object.
        for (int r = 0; r < this.scenario.blocks.size(); r++) {
            ScenarioBlock region = this.scenario.blocks.get(r);
            IBlockState expected = storedState(this.regionStates[r]);
            int stepX = region.toX >= region.fromX ? 1 : -1;
            int stepY = region.toY >= region.fromY ? 1 : -1;
            int stepZ = region.toZ >= region.fromZ ? 1 : -1;
            for (int x = region.fromX; ; x += stepX) {
                for (int y = region.fromY; ; y += stepY) {
                    for (int z = region.fromZ; ; z += stepZ) {
                        if (mc.theWorld.getBlockState(new BlockPos(x, y, z)) != expected) {
                            return false;
                        }
                        if (z == region.toZ) break;
                    }
                    if (y == region.toY) break;
                }
                if (x == region.toX) break;
            }
        }
        return true;
    }

    // --- player state ----------------------------------------------------------------

    /**
     * Reset before every run: all keys released, not sprinting, no motion, empty hand,
     * full food, nothing in use, {@code fallDistance} zero. {@code jumpTicks} is private
     * ({@code EntityLivingBase.java:204}); the settle ticks hold no jump anyway, and
     * {@code onLivingUpdate} sets it to 0 whenever jump is not held
     * ({@code EntityLivingBase.java:1524}).
     */
    private void resetPlayerState(Minecraft mc) {
        EntityPlayerSP player = mc.thePlayer;
        KeyBinding.unPressAllKeys();                        // KeyBinding.java:51
        player.setSprinting(false);
        player.motionX = 0.0;
        player.motionY = 0.0;
        player.motionZ = 0.0;
        player.fallDistance = 0.0f;                         // Entity.java:182
        player.clearItemInUse();                            // EntityPlayer.java:264
        player.inventory.setInventorySlotContents(
                player.inventory.currentItem, (ItemStack) null);   // InventoryPlayer.java:364
        player.getFoodStats().setFoodLevel(20);             // FoodStats.java:135
        if (this.scriptedInput != null) {
            this.scriptedInput.clear();
        }
        setSprintKey(false);
    }

    private void applyYaw(EntityPlayerSP player) {
        player.rotationYaw = this.scenario.yaw;
        player.rotationPitch = this.scenario.pitch;
    }

    private void rememberSettlePos(EntityPlayerSP player) {
        this.lastSettleX = player.posX;
        this.lastSettleY = player.posY;
        this.lastSettleZ = player.posZ;
        this.haveLastSettlePos = true;
    }

    /** @return what is wrong, or {@code null} when the steady state is good. */
    private String settleProblem(EntityPlayerSP player) {
        if (!player.onGround) {
            return "onGround is false";
        }
        if (Math.abs(player.motionY - GROUND_VEL_Y) > 1.0E-9) {
            return "vel y is " + player.motionY + ", expected " + GROUND_VEL_Y;
        }
        if (player.motionX != 0.0 || player.motionZ != 0.0) {
            return "horizontal vel is (" + player.motionX + ", " + player.motionZ + "), expected 0";
        }
        if (this.haveLastSettlePos
                && (player.posX != this.lastSettleX
                    || player.posY != this.lastSettleY
                    || player.posZ != this.lastSettleZ)) {
            return "position moved over the last 2 settle ticks";
        }
        return null;
    }

    private boolean atTarget(EntityPlayerSP player) {
        return Math.abs(player.posX - this.scenario.startX) <= POS_EPSILON
                && Math.abs(player.posY - this.scenario.startY) <= POS_EPSILON
                && Math.abs(player.posZ - this.scenario.startZ) <= POS_EPSILON;
    }

    private void setSprintKey(boolean down) {
        // ScriptedSprintKey is installed as GameSettings.keyBindSprint for the duration of
        // a run, so physics (EntityPlayerSP.java:607, :613 read it to START sprinting) and
        // the recorder (KairosRecorder.buildInput) both see the script, never the keyboard.
        this.scriptedSprintKey.setDown(down);
    }

    private void restoreMovementInput(Minecraft mc) {
        if (mc.thePlayer != null && this.originalMovementInput != null) {
            mc.thePlayer.movementInput = this.originalMovementInput;
        }
        if (this.originalSprintKey != null) {
            mc.gameSettings.keyBindSprint = this.originalSprintKey;
            this.originalSprintKey = null;
        }
        this.originalMovementInput = null;
        this.scriptedInput = null;
    }

    // --- files ---------------------------------------------------------------------

    private static File tracesDir(Minecraft mc) {
        return new File(mc.mcDataDir, "kairos/traces");     // Minecraft.java:306
    }

    private static String runFileName(String base, int index) {
        return runFileBase(base, index) + ".jsonl";
    }

    private static String runFileBase(String base, int index) {
        return base + "_run" + (index + 1 < 10 ? "0" : "") + (index + 1);
    }

    /**
     * Deletes this scenario's previous {@code runmany} outputs ({@code <name>_runNN.jsonl})
     * from {@code dir}. A single-run trace ({@code <name>.jsonl}) and every other
     * scenario's files are never touched. Silent: a file that cannot be deleted is left
     * for the recorder to refuse later.
     */
    private static void deleteOldRunFiles(File dir, String name) {
        if (!dir.isDirectory()) {
            return;
        }
        String[] children = dir.list();
        if (children == null) {
            return;
        }
        String prefix = name + "_run";
        String suffix = ".jsonl";
        for (String child : children) {
            if (!child.startsWith(prefix) || !child.endsWith(suffix)) {
                continue;
            }
            String middle = child.substring(prefix.length(), child.length() - suffix.length());
            // runFileBase writes at least two digits ("01", "02", ...); requiring that
            // keeps single-run files whose names merely start with the prefix safe.
            if (middle.length() < 2 || !isAllDigits(middle)) {
                continue;
            }
            new File(dir, child).delete();
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String normalizeId(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /**
     * Reads {@code data/scenarios/<name>.json}. The lookup itself (override, repo-root
     * marker, legacy fallback) lives in {@link ScenarioLocator} so it is unit tested in
     * {@code core} without Minecraft; see that class for the search order.
     */
    private static String readScenarioFile(Minecraft mc, String name) throws IOException {
        File found = ScenarioLocator.locate(name, mc.mcDataDir);
        return new String(Files.readAllBytes(found.toPath()), Charset.forName("UTF-8"));
    }

    private static int firstDifferingLine(byte[] a, byte[] b) {
        int line = 1;
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == '\n') {
                line++;
            }
            if (a[i] != b[j]) {
                return line;
            }
            i++;
            j++;
        }
        return line;
    }

    private static String fmt(double v) {
        return Double.toString(Math.rint(v * 1000.0) / 1000.0);
    }

    private static void chat(ICommandSender sender, String message) {
        send(sender, EnumChatFormatting.GRAY + "[Kairos] " + EnumChatFormatting.RESET + message);
    }

    private static void chatRed(ICommandSender sender, String message) {
        send(sender, EnumChatFormatting.RED + "[Kairos] " + message);
    }

    private static void send(ICommandSender sender, String message) {
        ChatComponentText component = new ChatComponentText(message);  // ChatComponentText.java:13
        if (sender != null) {
            sender.addChatMessage(component);
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            player.addChatMessage(component);                          // EntityPlayerSP.java:416
        }
    }

    /**
     * The fake {@code MovementInput}. Physics reads only these fields, so the real keyboard
     * cannot leak into a run. It reproduces {@code MovementInputFromOptions} including the
     * {@code 0.3} sneak scaling ({@code MovementInputFromOptions.java:39-40}).
     */
    static final class ScenarioMovementInput extends MovementInput {

        private int keyW;
        private int keyA;
        private int keyS;
        private int keyD;
        private int keyJump;
        private int keySneak;

        void clear() {
            this.keyW = 0;
            this.keyA = 0;
            this.keyS = 0;
            this.keyD = 0;
            this.keyJump = 0;
            this.keySneak = 0;
            this.moveForward = 0.0f;
            this.moveStrafe = 0.0f;
            this.jump = false;
            this.sneak = false;
        }

        void apply(ScenarioInput segment) {
            this.keyW = segment.w;
            this.keyA = segment.a;
            this.keyS = segment.s;
            this.keyD = segment.d;
            this.keyJump = segment.jump;
            this.keySneak = segment.sneak;
        }

        @Override
        public void updatePlayerMoveState() {
            float forward = (float) (this.keyW - this.keyS);
            float strafe = (float) (this.keyA - this.keyD);
            if (this.keySneak != 0) {
                forward = (float) ((double) forward * 0.3);
                strafe = (float) ((double) strafe * 0.3);
            }
            this.moveForward = forward;
            this.moveStrafe = strafe;
            this.jump = this.keyJump != 0;
            this.sneak = this.keySneak != 0;
        }
    }

    /**
     * The sprint key installed in {@code GameSettings.keyBindSprint} for the duration of
     * a run. It cannot be touched by the physical keyboard: {@code setKeyBindState}
     * resolves bindings by key code through a static map (KeyBinding.java:41-48) and this
     * binding uses {@code Keyboard.KEY_NONE} (0), for which {@code setKeyBindState} and
     * {@code onTick} return immediately (KeyBinding.java:32-34, :42-44). The keyboard
     * loop also re-maps event key 0 away from 0 before dispatching
     * (Minecraft.java:1562), so no physical event can ever carry key code 0. The
     * {@code isKeyDown} read (KeyBinding.java:81; called from EntityPlayerSP.java:607,
     * :613) is overridden, so even {@code unPressAllKeys} (KeyBinding.java:51-55, called
     * from {@code Minecraft.setIngameNotInFocus}, Minecraft.java:1261) cannot clear a
     * scripted press.
     */
    static final class ScriptedSprintKey extends KeyBinding {

        private boolean down;

        ScriptedSprintKey() {
            // Same registration the vanilla bindings use (KeyBinding.java:68-76); key code
            // 0 keeps the static hash entry inert (see above). It is not added to
            // GameSettings.keyBindings, so it never shows in Options -> Controls.
            super("key.kairos.scripted_sprint", Keyboard.KEY_NONE, "key.categories.kairos");
        }

        void setDown(boolean down) {
            this.down = down;
        }

        @Override
        public boolean isKeyDown() {
            return this.down;
        }
    }
}
