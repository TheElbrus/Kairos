package dev.kairos.loader;

import dev.kairos.core.trace.TraceInput;
import dev.kairos.core.trace.TraceLine;
import dev.kairos.core.trace.TraceWriter;

import net.minecraft.client.Minecraft; // mc-src/net/minecraft/client/Minecraft.java
import net.minecraft.client.entity.EntityPlayerSP; // mc-src/net/minecraft/client/entity/EntityPlayerSP.java
import net.minecraft.client.settings.GameSettings; // mc-src/net/minecraft/client/settings/GameSettings.java
import net.minecraft.command.ICommandSender; // mc-src/net/minecraft/command/ICommandSender.java
import net.minecraft.util.ChatComponentText; // mc-src/net/minecraft/util/ChatComponentText.java
import net.minecraft.util.EnumChatFormatting; // mc-src/net/minecraft/util/EnumChatFormatting.java
import net.minecraft.util.MovementInput; // mc-src/net/minecraft/util/MovementInput.java
import net.minecraftforge.event.world.WorldEvent; // mc-src/net/minecraftforge/event/world/WorldEvent.java
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent; // mc-src/net/minecraftforge/fml/common/gameevent/TickEvent.java

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

/**
 * Writes one trace line per client tick while recording (PLAN.md Phase 0, task 0.4).
 *
 * <p>Semantics (PLAN.md 3.1, ADR 0001, never change): line {@code N} records the input
 * applied during tick {@code N} and the player state at the END of tick {@code N}. One
 * extra line with {@code tick:-1} carries the start state, so the simulator has an
 * anchor for tick 0. The byte-level format is frozen in ADR 0010.
 *
 * <p>All Minecraft API uses are cited from mc-src/ (MCP {@code stable_22}).
 */
public final class KairosRecorder {

    /** Minecraft version written into every line. Mirrors {@code mcVersion} in gradle.properties. */
    static final String MC_VERSION = "1.8.9";

    /** Relative to {@link Minecraft#mcDataDir} (i.e. the dev run directory). Gitignored. */
    private static final String TRACE_DIR = "kairos/traces";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Keeps the scenario name from escaping the traces directory. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private String scenario;
    private File file;
    private Writer writer;

    /** True between "recording enabled" and the first ClientTickEvent.START (the tick -1 line). */
    private boolean startLinePending;
    private int nextTick;

    /** {@code ticksExisted} of the last line written, for the gap guard (absolute). */
    private int lastTe;

    /**
     * Absolute {@code ticksExisted} seen on the {@code tick:-1} line. Written {@code te}
     * values are {@code ticksExisted - anchorTe}, so they are 0, 1, 2, ... and do not
     * depend on how long the world has been loaded (ADR 0010). The gap guard keeps using
     * the absolute {@link #lastTe}.
     */
    private int anchorTe;

    public boolean isRecording() {
        return this.writer != null;
    }

    public String getScenario() {
        return this.scenario;
    }

    /** The open trace file, or {@code null} when not recording. Used by the scenario runner. */
    public File getFile() {
        return this.file;
    }

    /** Number of tick lines written so far (the {@code tick:-1} anchor is not counted). */
    public int getRecordedTicks() {
        return this.nextTick;
    }

    // --- Commands ---------------------------------------------------------------

    /** {@code /kairos record start <name>}. Refuses to overwrite an existing trace. */
    public void start(String name, ICommandSender sender) {
        start(name, name, sender);
    }

    /**
     * Starts a recording to {@code <fileName>.jsonl} while stamping every line with
     * {@code scenarioLabel} in its {@code scenario} field. The scenario harness (task 0.5)
     * will use this so a {@code runmany} batch writes one file per run
     * ({@code <base>_runNN.jsonl}) but every file still carries the SAME base scenario
     * label -- otherwise the run index leaks into the trace and the byte-for-byte
     * determinism check fails on line 1 (ADR 0010). Refuses to overwrite an existing file.
     */
    public void start(String fileName, String scenarioLabel, ICommandSender sender) {
        if (this.writer != null) {
            chat(sender, "already recording '" + this.scenario + "'");
            return;
        }
        if (!VALID_NAME.matcher(fileName).matches()) {
            chatRed(sender, "invalid name '" + fileName + "' (use letters, digits, _ or -, max 64)");
            return;
        }
        if (!VALID_NAME.matcher(scenarioLabel).matches()) {
            chatRed(sender, "invalid scenario label '" + scenarioLabel
                    + "' (use letters, digits, _ or -, max 64)");
            return;
        }

        // Minecraft.mcDataDir is the game's data directory (Minecraft.java:306); in the dev
        // client that is loader-1.8.9/run, so traces land in run/kairos/traces/.
        File dir = new File(Minecraft.getMinecraft().mcDataDir, TRACE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            chatRed(sender, "could not create " + dir.getAbsolutePath());
            return;
        }

        File target = new File(dir, fileName + ".jsonl");
        if (target.exists()) {
            // Never overwrite: a previous trace is evidence and may be a golden file.
            chatRed(sender, "trace already exists, refusing to overwrite: " + target.getAbsolutePath());
            return;
        }

        try {
            // UTF-8 explicitly, and `\n` is written by hand below, so the file does not
            // depend on the platform's default charset or line separator (ADR 0010).
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(target), UTF8));
        } catch (IOException e) {
            chatRed(sender, "could not open " + target.getAbsolutePath() + ": " + e);
            return;
        }

        this.scenario = scenarioLabel;
        this.file = target;
        this.startLinePending = true;
        this.nextTick = 0;
        this.lastTe = 0;

        chat(sender, "recording to " + target.getAbsolutePath());
        chat(sender, "state will be captured at the next tick start");
    }

    /** {@code /kairos record stop}. */
    public void stop(ICommandSender sender) {
        if (this.writer == null) {
            chat(sender, "not recording");
            return;
        }
        String name = this.scenario;
        int ticks = this.nextTick;
        close();
        this.startLinePending = false;
        this.scenario = null;
        this.file = null;
        chat(sender, "stopped '" + name + "' after " + ticks + " ticks");
    }

    /** {@code /kairos record status}. */
    public void status(ICommandSender sender) {
        if (this.writer == null) {
            chat(sender, "not recording. usage: " + CommandKairos.USAGE);
            return;
        }
        chat(sender, "recording '" + this.scenario + "'"
                + (this.startLinePending ? " (waiting for the first tick)" : " at tick " + this.nextTick)
                + " -> " + this.file.getAbsolutePath());
    }

    // --- Events -----------------------------------------------------------------

    /**
     * Records at {@code Phase.END}: the event is posted by
     * {@code FMLCommonHandler.onPostClientTick()} (FMLCommonHandler.java:336), which
     * {@code Minecraft.runTick()} reaches after {@code theWorld.updateEntities()} has run
     * the player's {@code onLivingUpdate()} -> {@code moveEntityWithHeading()} for this
     * tick (Minecraft.java:1739 updateEntities, :1777 post). {@code Phase.START}
     * (posted at FMLCommonHandler.java:331) would still hold the previous tick's end state.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (this.writer == null) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;   // Minecraft.java:252

        if (event.phase == TickEvent.Phase.START) {                   // TickEvent.java:14
            if (!this.startLinePending || player == null) {
                return;
            }
            // The start state for tick 0, with every input zero (ADR 0010). `te` on this
            // line is 0 by definition; every later line is `ticksExisted - anchorTe`.
            this.anchorTe = player.ticksExisted;                      // Entity.java:215
            writeLine(player, -1, TraceInput.zero(), 0);
            this.startLinePending = false;
            this.lastTe = player.ticksExisted;
            return;
        }

        if (this.startLinePending) {
            // Recording was enabled mid-tick; it takes effect from the next tick.
            return;
        }
        if (player == null) {
            stopInvalid();
            return;
        }
        // Gap guard: ticksExisted is incremented once per entity tick in
        // World.updateEntityWithOptionalForce (World.java:1470, ++ at :1487). If it did
        // not advance by exactly one, the player was not ticked (chunk unloading, pause,
        // a server correction) and the trace can no longer be replayed.
        if (player.ticksExisted != this.lastTe + 1) {
            stopInvalid();
            return;
        }
        writeLine(player, this.nextTick, buildInput(player), player.ticksExisted - this.anchorTe);
        this.lastTe = player.ticksExisted;
        this.nextTick++;
    }

    /**
     * Closes the file when the world goes away. {@code Minecraft.loadWorld} posts this
     * (Minecraft.java:1852, post at :1854) for both "quit to title" and window close.
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (this.writer == null) {
            return;
        }
        String name = this.scenario;
        close();
        this.startLinePending = false;
        this.scenario = null;
        this.file = null;
        chat(null, "stopped '" + name + "' (world unloaded)");
    }

    // --- Internals --------------------------------------------------------------

    private static TraceInput buildInput(EntityPlayerSP player) {
        // EntityPlayerSP.movementInput is public (EntityPlayerSP.java:101); MovementInput's
        // moveStrafe/moveForward/jump/sneak are public fields (MovementInput.java:14-20).
        // Read at END of tick, so they already include MovementInputFromOptions' sneak *0.3
        // (:39-40) and EntityPlayerSP.onLivingUpdate's item-use *0.2 (:596-599) (ADR 0010).
        MovementInput mi = player.movementInput;
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        int sprint = settings.keyBindSprint.isKeyDown() ? 1 : 0;      // GameSettings.java:145, KeyBinding.java:81
        return TraceInput.fromAxes(
                mi.jump ? 1 : 0,
                mi.sneak ? 1 : 0,
                sprint,
                player.rotationYaw,                                   // Entity.java:134
                player.rotationPitch,
                mi.moveForward,
                mi.moveStrafe);
    }

    private void writeLine(EntityPlayerSP player, int tick, TraceInput in, int relativeTe) {
        TraceLine line = new TraceLine(
                MC_VERSION, this.scenario, tick, in,
                player.posX, player.posY, player.posZ,                 // Entity.java:110
                player.motionX, player.motionY, player.motionZ,        // Entity.java:122
                player.onGround,                                       // Entity.java:145
                player.isSprinting(),                                  // Entity.java:1741
                player.isUsingItem(),                                  // EntityPlayer.java:245
                player.isCollidedHorizontally,                         // Entity.java:149
                player.isCollidedVertically,                           // Entity.java:153
                relativeTe);
        try {
            this.writer.write(TraceWriter.toJsonLine(line));
            this.writer.write('\n');                     // always \n, never the platform default
            this.writer.flush();                         // flush per line: a crash loses at most one
        } catch (IOException e) {
            stopInvalid();
        } catch (IllegalArgumentException e) {
            // TraceWriter refuses NaN / infinity; that means the game state is not
            // representable, so the trace is worthless from here on.
            stopInvalid();
        }
    }

    private void stopInvalid() {
        close();
        this.startLinePending = false;
        this.scenario = null;
        this.file = null;
        chatRed(null, "player was not ticked: trace invalid");
    }

    private void close() {
        if (this.writer != null) {
            try {
                this.writer.flush();
                this.writer.close();
            } catch (IOException ignored) {
                // Nothing useful to do while shutting down; the lines written so far are
                // already flushed.
            }
            this.writer = null;
        }
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
}
