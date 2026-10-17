package dev.kairos.loader;

// Every Minecraft API used here is cited from the decompiled 1.8.9 sources in mc-src/
// (MCP stable_22), as AGENTS.md requires. mc-src/ is gitignored and never committed.

import dev.kairos.core.Kairos; // bundled into the mod jar from the `core` library
import net.minecraft.client.Minecraft; // mc-src/net/minecraft/client/Minecraft.java
import net.minecraft.client.settings.KeyBinding; // mc-src/net/minecraft/client/settings/KeyBinding.java
import net.minecraft.util.ChatComponentText; // mc-src/net/minecraft/util/ChatComponentText.java
import net.minecraftforge.client.ClientCommandHandler; // mc-src/net/minecraftforge/client/ClientCommandHandler.java
import net.minecraftforge.common.MinecraftForge; // mc-src/net/minecraftforge/common/MinecraftForge.java
import net.minecraftforge.fml.client.registry.ClientRegistry; // mc-src/net/minecraftforge/fml/client/registry/ClientRegistry.java
import net.minecraftforge.fml.common.Mod; // mc-src/net/minecraftforge/fml/common/Mod.java
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Loader entry point for Kairos: a startup banner and keybind (task 0.3) plus the trace
 * recorder wired to {@code /kairos record ...} (task 0.4). No physics and no planning
 * live here (AGENTS.md: the loader is thin).
 */
// The explicit version is deliberate: FMLModContainer reads the annotation's version
// first (mc-src/net/minecraftforge/fml/common/FMLModContainer.java:196) and warns when it
// is empty (Mod.java:81 defaults it to ""). Keep in sync with gradle.properties.
@Mod(modid = "kairos", version = "0.1.0", useMetadata = true)
public class KairosMod {

    // Keep in sync with @Mod(version = ...) and gradle.properties (see CREDITS.md workflow).
    private static final String MOD_VERSION = "0.1.0";

    /** Task 0.4: writes one trace line per client tick while enabled (ADR 0010). */
    private final KairosRecorder recorder = new KairosRecorder();

    // KeyBinding(String, int, String): KeyBinding.java:68. Raw LWJGL key codes are what
    // GameSettings itself uses (GameSettings.java:139-143), e.g. keyBindJump = 57.
    // Keyboard.KEY_P is the LWJGL 2 constant (org.lwjgl.input.Keyboard, library, not mc-src).
    private final KeyBinding helloKey = new KeyBinding(
            "key.kairos.hello",
            Keyboard.KEY_P,
            "key.categories.kairos");

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        System.out.println("[Kairos] Kairos " + MOD_VERSION + " loaded. core PLAN_VERSION=" + Kairos.PLAN_VERSION);

        // Registers the keybinding so it appears in Options -> Controls (ClientRegistry.java:53).
        ClientRegistry.registerKeyBinding(this.helloKey);
        // Static bus for gameplay events (MinecraftForge.java:28); register() at EventBus.java:45.
        MinecraftForge.EVENT_BUS.register(this);

        // The recorder listens for client ticks and world unloads.
        MinecraftForge.EVENT_BUS.register(this.recorder);

        // Client-side command, so /kairos works in singleplayer without the server:
        //   ClientCommandHandler.instance  mc-src/net/minecraftforge/client/ClientCommandHandler.java:29
        //   registerCommand(ICommand)     mc-src/net/minecraft/command/CommandHandler.java:119
        ClientCommandHandler.instance.registerCommand(new CommandKairos(this.recorder));
    }

    // TickEvent.Phase at TickEvent.java:14; ClientTickEvent at TickEvent.java:34.
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft(); // Minecraft.java:2112
        if (mc.thePlayer == null) { // Minecraft.java:252
            return;
        }
        // isPressed() is the tick-exact edge check (KeyBinding.java:92).
        while (this.helloKey.isPressed()) {
            // EntityPlayerSP.addChatMessage(IChatComponent): EntityPlayerSP.java:416.
            // ChatComponentText(String): ChatComponentText.java:13.
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    "[Kairos] keybind works. core PLAN_VERSION=" + Kairos.PLAN_VERSION));
        }
    }
}
