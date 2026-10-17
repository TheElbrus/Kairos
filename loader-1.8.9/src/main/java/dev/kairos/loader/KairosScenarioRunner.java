package dev.kairos.loader;

// Every Minecraft API used here is cited from the decompiled source in mc-src/ (MCP
// `stable_22`), as AGENTS.md requires. mc-src/ is gitignored and never committed.

import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioLocator;
import dev.kairos.core.scenario.ScenarioParser;

import net.minecraft.block.Block; // mc-src/net/minecraft/block/Block.java
import net.minecraft.block.state.IBlockState; // mc-src/net/minecraft/block/state/IBlockState.java
import net.minecraft.client.Minecraft; // mc-src/net/minecraft/client/Minecraft.java
import net.minecraft.client.entity.EntityPlayerSP; // mc-src/net/minecraft/client/entity/EntityPlayerSP.java
import net.minecraft.client.settings.KeyBinding; // mc-src/net/minecraft/client/settings/KeyBinding.java
import net.minecraft.command.ICommandSender; // mc-src/net/minecraft/command/ICommandSender.java
import net.minecraft.entity.player.EntityPlayerMP; // mc-src/net/minecraft/entity/player/EntityPlayerMP.java
import net.minecraft.item.ItemStack; // mc-src/net/minecraft/item/ItemStack.java
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

    private enum Phase {
        IDLE, TELEPORT, BUILD, TELEPORT2, SETTLE, RECORD
    }

    private final KairosRecorder recorder;

    private Phase phase = Phase.IDLE;
    private Scenario scenario;
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
        this.runsTotal = runs;
        this.runIndex = 0;
        beginRun(sender);
    }

    private void beginRun(ICommandSender sender) {
        this.traceName = this.runsTotal > 1 ? runFileBase(this.scenario.name, this.runIndex)
                : this.scenario.name;
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
        chat(sender, (this.runsTotal > 1 ? ("run " + (this.runIndex + 1) + "/" + this.runsTotal + ": ") : "")
                + "placing the player for '" + this.scenario.name + "'");
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
                    abort("client world does not show every scenario block");
                    return;
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
                    // File name carries the run index (traceName); every run stamps the
                    // SAME base scenario label, so the scenario field never differs
                    // between runs (ADR 0010).
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
        chatRed(null, "scenario aborted: " + why);
        this.phase = Phase.IDLE;
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

    /** Fills every scenario region, flag 2 (sync to clients, no neighbour update). */
    private void buildBlocks(Minecraft mc) {
        final MinecraftServer server = mc.getIntegratedServer();
        final int dim = mc.theWorld.provider.getDimensionId();
        final ScenarioBlock[] blocks =
                this.scenario.blocks.toArray(new ScenarioBlock[this.scenario.blocks.size()]);
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                WorldServer world = server.worldServerForDimension(dim);
                for (ScenarioBlock region : blocks) {
                    Block block = Block.getBlockFromName(normalizeId(region.id));   // Block.java:366
                    if (block == null) {
                        continue;   // clientHasBlocks() reports the unknown id
                    }
                    IBlockState state = block.getDefaultState();
                    int stepX = region.toX >= region.fromX ? 1 : -1;
                    int stepY = region.toY >= region.fromY ? 1 : -1;
                    int stepZ = region.toZ >= region.fromZ ? 1 : -1;
                    for (int x = region.fromX; ; x += stepX) {
                        for (int y = region.fromY; ; y += stepY) {
                            for (int z = region.fromZ; ; z += stepZ) {
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
            }
        });
        this.waitTicks = 0;
    }

    /** @return true when the CLIENT world shows every scenario block (physics runs there). */
    private boolean clientHasBlocks(Minecraft mc) {
        for (ScenarioBlock region : this.scenario.blocks) {
            Block expected = Block.getBlockFromName(normalizeId(region.id));
            if (expected == null) {
                abort("unknown block id '" + region.id + "'");
                return false;
            }
            int stepX = region.toX >= region.fromX ? 1 : -1;
            int stepY = region.toY >= region.fromY ? 1 : -1;
            int stepZ = region.toZ >= region.fromZ ? 1 : -1;
            for (int x = region.fromX; ; x += stepX) {
                for (int y = region.fromY; ; y += stepY) {
                    for (int z = region.fromZ; ; z += stepZ) {
                        if (mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock() != expected) {
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
