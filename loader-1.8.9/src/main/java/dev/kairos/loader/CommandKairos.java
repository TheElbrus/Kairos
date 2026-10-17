package dev.kairos.loader;

import net.minecraft.command.CommandBase; // mc-src/net/minecraft/command/CommandBase.java
import net.minecraft.command.ICommandSender; // mc-src/net/minecraft/command/ICommandSender.java
import net.minecraft.util.ChatComponentText; // mc-src/net/minecraft/util/ChatComponentText.java
import net.minecraft.util.EnumChatFormatting; // mc-src/net/minecraft/util/EnumChatFormatting.java

/**
 * {@code /kairos record start <name> | stop | status}.
 *
 * <p>Client-side only, registered on {@code ClientCommandHandler}. That makes it work in
 * singleplayer without touching the integrated server and without a dependency on
 * anything the server knows about.
 *
 * <p>APIs cited from mc-src/: {@code ClientCommandHandler} is
 * {@code net.minecraftforge.client.ClientCommandHandler}; {@code registerCommand(ICommand)}
 * comes from {@code CommandHandler.java:119}. {@code GuiScreen.sendChatMessage} routes
 * chat input to it before the server sees it ({@code GuiScreen.java:337}, dispatch at
 * :341). {@code CommandBase} already implements {@code getCommandAliases}, {@code compareTo},
 * {@code isUsernameIndex} and {@code addTabCompletionOptions}, so only the methods below
 * are needed.
 */
public final class CommandKairos extends CommandBase {

    static final String USAGE =
            "/kairos record start <name>  |  /kairos record stop  |  /kairos record status";

    private final KairosRecorder recorder;

    public CommandKairos(KairosRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public String getCommandName() {
        return "kairos";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        // A client-side command: the server permission model does not apply. CommandBase
        // would otherwise demand permission level 4 (CommandBase.java:42-44, :52-54),
        // which fails outside creative.
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length >= 2 && "record".equals(args[0])) {
            processRecord(sender, args);
        } else {
            usage(sender);
        }
    }

    private void processRecord(ICommandSender sender, String[] args) {
        if ("start".equals(args[1])) {
            if (args.length < 3) {
                usage(sender);
                return;
            }
            // Only the first token is used, so "/kairos record start foo bar" is not a path.
            this.recorder.start(args[2], sender);
        } else if ("stop".equals(args[1])) {
            this.recorder.stop(sender);
        } else if ("status".equals(args[1])) {
            this.recorder.status(sender);
        } else {
            usage(sender);
        }
    }

    private static void usage(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.GRAY + "[Kairos] " + USAGE));
    }
}
