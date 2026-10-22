package dev.kairos.loader;

import net.minecraft.block.Block; // mc-src/net/minecraft/block/Block.java
import net.minecraft.block.BlockFence; // mc-src/net/minecraft/block/BlockFence.java
import net.minecraft.block.BlockFenceGate; // mc-src/net/minecraft/block/BlockFenceGate.java
import net.minecraft.block.BlockPane; // mc-src/net/minecraft/block/BlockPane.java
import net.minecraft.block.BlockWall; // mc-src/net/minecraft/block/BlockWall.java
import net.minecraft.block.material.Material; // mc-src/net/minecraft/block/material/Material.java
import net.minecraft.block.properties.IProperty; // mc-src/net/minecraft/block/properties/IProperty.java
import net.minecraft.block.state.IBlockState; // mc-src/net/minecraft/block/state/IBlockState.java
import net.minecraft.client.Minecraft; // mc-src/net/minecraft/client/Minecraft.java
import net.minecraft.command.ICommandSender; // mc-src/net/minecraft/command/ICommandSender.java
import net.minecraft.init.Blocks; // mc-src/net/minecraft/init/Blocks.java
import net.minecraft.profiler.Profiler; // mc-src/net/minecraft/profiler/Profiler.java
import net.minecraft.util.AxisAlignedBB; // mc-src/net/minecraft/util/AxisAlignedBB.java
import net.minecraft.util.BlockPos; // mc-src/net/minecraft/util/BlockPos.java
import net.minecraft.util.ChatComponentText; // mc-src/net/minecraft/util/ChatComponentText.java
import net.minecraft.util.EnumChatFormatting; // mc-src/net/minecraft/util/EnumChatFormatting.java
import net.minecraft.util.EnumFacing; // mc-src/net/minecraft/util/EnumFacing.java
import net.minecraft.util.ResourceLocation; // mc-src/net/minecraft/util/ResourceLocation.java
import net.minecraft.world.World; // mc-src/net/minecraft/world/World.java
import net.minecraft.world.WorldProviderSurface; // mc-src/net/minecraft/world/WorldProviderSurface.java
import net.minecraft.world.WorldSettings; // mc-src/net/minecraft/world/WorldSettings.java
import net.minecraft.world.WorldType; // mc-src/net/minecraft/world/WorldType.java
import net.minecraft.world.chunk.IChunkProvider; // mc-src/net/minecraft/world/chunk/IChunkProvider.java
import net.minecraft.world.storage.WorldInfo; // mc-src/net/minecraft/world/storage/WorldInfo.java

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /kairos dumpblocks}: dumps every registered block state with its collision boxes,
 * slipperiness and climbable flag to {@code run/kairos/blocks.json} (task 1.0, PLAN.md
 * Phase 1). It lives in the loader because it reads the live game registry, but contains
 * no physics (ADR 0002; AGENTS.md: the loader is thin).
 *
 * <p><b>Authoritative collision chain.</b> The dumper runs the exact chain the game runs
 * for entity-vs-block collision: {@code Entity.moveEntity} (Entity.java:553) asks
 * {@code World.getCollidingBoundingBoxes(Entity, AxisAlignedBB)} (World.java:1003), which
 * calls {@code Block.addCollisionBoxesToList} (Block.java:613); the default implementation
 * there tests {@code mask.intersectsWith(...)} and adds
 * {@code Block.getCollisionBoundingBox} (Block.java:620), which builds a box from the
 * block instance's mutable bounds. (The task text mentioned
 * {@code getCollisionBoundingBoxFromPool} / {@code addCollidingBlockToList}: those method
 * names do not exist in this MCP stable_22 mapping; the chain above is the real one.
 * {@code World.getCollisionBoxes} (World.java:1067) is only the null-entity convenience
 * wrapper.) The dumper passes a huge mask so every box is captured, entity {@code null}
 * (the non-boat branch; only {@code BlockLilyPad.addCollisionBoxesToList}
 * (BlockLilyPad.java:33-34) reads the entity), and a probe world whose only non-air block
 * is the state under test, so neighbour-sensitive shapes (fence, pane, wall, stairs,
 * chest, ...) come out as the lone block.
 *
 * <p><b>Bounds priming.</b> {@code getCollisionBoundingBox}'s default reads instance
 * bounds, and some blocks only ever update them from
 * {@code setBlockBoundsBasedOnState} (Block.java:918): anvils (BlockAnvil.java:76-83),
 * beds (constructor {@code setBedBounds}, BlockBed.java:36), chests (BlockChest.java:65).
 * In the live client the bounds of such a block are last set by the render path, which
 * calls {@code setBlockBoundsBasedOnState} before every model
 * (BlockModelRenderer.java:34); the crosshair ray trace does the same (Block.java:776).
 * So before each collision query the dumper also calls
 * {@code setBlockBoundsBasedOnState} -- this makes the dump the "just rendered this
 * block" value, which is the only reproducible one, and is a no-op or redundant for
 * every block whose collision override computes its own box.
 *
 * <p><b>Factual note vs the task text.</b> Ladders DO have a collision box in 1.8.9:
 * {@code BlockLadder.getCollisionBoundingBox} (BlockLadder.java:33-36) sets the thin
 * 1/8-thick bounds of the FACING side and returns them. Vines do not
 * (BlockVine.java:130-133). Both are {@code climbable}: {@code Block.isLadder}
 * (Block.java:1250) is overridden to true in BlockLadder.java:137 and
 * BlockVine.java:366. The game source is authoritative here; the dump is never edited
 * to match the task text.
 *
 * <p><b>Known eccentricity (documented, not special-cased).</b>
 * {@code minecraft:piston_extension} ({@code BlockPistonMoving}) derives its collision
 * box from a tile entity (BlockPistonMoving.java:125-137, :139-161); the probe has none,
 * so its dumped box is the instance default. In the world that block only exists while a
 * piston moves, so its static value is meaningless anyway. No block is skipped and no
 * state is special-cased.
 *
 * <p><b>Output format (schema 2).</b> UTF-8, LF only, keys and states sorted, numbers
 * formatted with {@code Double.toString}/{@code Float.toString} (locale-independent, same
 * precedent as {@code core}'s TraceWriter). One JSON object per line for block states:
 * <pre>
 * {"schema":2,"mc":"1.8.9","generator":...,"about":...,"counts":{"blocks":N,"states":M},
 *  "blocks":[{"name":"minecraft:acacia_door",
 *    "connector":{"cube":false,"pane":false},
 *    "states":[
 *      {"props":{"facing":"east","half":"lower","open":"false","powered":"false"},
 *       "boxes":[...],"slipperiness":0.6,"climbable":false,"solid":0}, ... ]}, ... ]}
 * </pre>
 * {@code boxes} are {@code [minX,minY,minZ,maxX,maxY,maxZ]} relative to the block
 * origin. Schema 2 (task 1.4) adds the generated connector facts: {@code connector} is
 * block-level and holds {@code kind} ("fence"/"wall"/"pane"/"gate", absent otherwise),
 * {@code group} (same-{@code Material} fence group / the wall block itself),
 * {@code cube} ({@code material.isOpaque() && isFullCube() && material != gourd}, the
 * non-fence/wall {@code canConnectTo} clause) and {@code pane}
 * ({@code BlockPane.canPaneConnectToBlock}); {@code solid} is the state's
 * {@code Block.isSideSolid} mask, bit {@code EnumFacing.ordinal()}. A dumper-side
 * cross-check (registry state sum vs serialized states) aborts with a red chat error and
 * writes nothing on mismatch. On success it prints one gray line with the SHA-256 of the
 * exact bytes; running the command twice must produce the same hash.
 */
public final class BlockDumper {

    /** Relative to {@link Minecraft#mcDataDir}, like the recorder's traces directory. */
    private static final String DUMP_DIR = "kairos";

    private static final String DUMP_NAME = "blocks.json";
    private static final String TMP_NAME = "blocks.json.tmp";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    /** Huge mask around the origin so every collision box intersects it. */
    private static final AxisAlignedBB MASK = new AxisAlignedBB(-2.0, -2.0, -2.0, 3.0, 3.0, 3.0);

    private static final String ABOUT =
            "schema 2: one entry per registry block (sorted by name), one line per valid block state"
            + " (sorted by properties); props = state property values; boxes = entity-vs-block collision"
            + " boxes for this state in isolation (air neighbours, no entity), relative to the block"
            + " origin, [minX,minY,minZ,maxX,maxY,maxZ]; slipperiness = Block.slipperiness as a raw"
            + " float; climbable = Block.isLadder; solid = Block.isSideSolid mask, bit"
            + " EnumFacing.ordinal() (DOWN,UP,NORTH,SOUTH,WEST,EAST); connector = generated connection"
            + " facts (kind fence/wall/pane/gate, group, cube = opaque+isFullCube+not gourd, pane ="
            + " canPaneConnectToBlock). File is UTF-8 with LF line endings.";

    /** {@code /kairos dumpblocks}. Writes {@code run/kairos/blocks.json} or a red chat error. */
    public void dump(ICommandSender sender) {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, DUMP_DIR); // Minecraft.java:306
        try {
            Dump dump = buildDump();
            byte[] bytes = dump.json.getBytes(UTF8);

            Path dirPath = dir.toPath();
            Files.createDirectories(dirPath);
            Path target = dirPath.resolve(DUMP_NAME);
            Path tmp = dirPath.resolve(TMP_NAME);
            try {
                // Write to a temp file and move it into place, so no partial file is ever
                // visible even if the JVM dies mid-write. Overwriting is allowed: the user
                // is expected to run the command twice and compare the hashes.
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // The rename never happened; the original error is the one that matters.
                }
                throw new IOException("could not write " + target + ": " + e, e);
            }

            chat(sender, "blocks=" + dump.blocks + " states=" + dump.states
                    + " -> " + target + " SHA-256 " + sha256Hex(bytes));
        } catch (IOException e) {
            chatRed(sender, "dump failed: " + e.getMessage());
        } catch (RuntimeException e) {
            chatRed(sender, "dump failed: " + e);
        }
    }

    // --- Dump construction ------------------------------------------------------

    private static Dump buildDump() {
        ProbeWorld probe = new ProbeWorld();

        // RegistryNamespaced.getKeys() returns an unordered set (RegistrySimple.java:49);
        // sort by the registry name so the file order is fully determined.
        List<ResourceLocation> names =
                new ArrayList<ResourceLocation>(Block.blockRegistry.getKeys()); // Block.java:204
        Collections.sort(names, new Comparator<ResourceLocation>() {
            @Override
            public int compare(ResourceLocation a, ResourceLocation b) {
                return a.toString().compareTo(b.toString());
            }
        });

        // Fence groups are Material identities (BlockFence.canConnectTo: blockMaterial ==
        // this.blockMaterial, BlockFence.java:142). Name each group by the first fence
        // block of that material in sorted order, so the dump is deterministic and the
        // string carries no identity hash.
        Map<Material, String> fenceGroups = new IdentityHashMap<Material, String>();
        for (ResourceLocation name : names) {
            Block block = Block.blockRegistry.getObject(name);
            if (block instanceof BlockFence && !fenceGroups.containsKey(block.getMaterial())) {
                fenceGroups.put(block.getMaterial(), name.toString());
            }
        }

        StringBuilder blocks = new StringBuilder(1 << 19);
        int blockCount = 0;
        int stateCount = 0;
        int expectedStates = 0;

        for (ResourceLocation name : names) {
            Block block = Block.blockRegistry.getObject(name); // RegistryNamespaced.java:37
            if (block == null) {
                throw new IllegalStateException("registry key " + name + " has no block");
            }

            // getValidStates() is the cartesian product of the properties, built once per
            // block (BlockState.java:82).
            List<IBlockState> states = block.getBlockState().getValidStates(); // Block.java:1218
            if (states.isEmpty()) {
                throw new IllegalStateException("block " + name + " has no states");
            }

            // Sort states by their full property map so the file never depends on the
            // library's iteration order (BlockState.java:60 already sorts property names,
            // but the dumper does not rely on that).
            TreeMap<String, String> stateLines = new TreeMap<String, String>();
            for (IBlockState state : states) {
                TreeMap<String, String> props = new TreeMap<String, String>();
                for (Map.Entry<IProperty, Comparable> entry : state.getProperties().entrySet()) {
                    props.put(entry.getKey().getName(), valueName(entry.getKey(), entry.getValue()));
                }
                String key = props.toString();
                String line = stateJson(block, state, probe, props);
                if (stateLines.put(key, line) != null) {
                    throw new IllegalStateException(name + " has two states " + key);
                }
            }
            expectedStates += states.size();

            if (blockCount > 0) {
                blocks.append(",\n");
            }
            blocks.append("  {\"name\": \"").append(escape(name.toString()))
                    .append("\", \"connector\": ")
                    .append(connectorJson(connector(block, name, fenceGroups)))
                    .append(", \"states\": [\n");
            int i = 0;
            for (String line : stateLines.values()) {
                blocks.append("    ").append(line);
                if (++i < stateLines.size()) {
                    blocks.append(',');
                }
                blocks.append('\n');
            }
            blocks.append("  ]}");

            stateCount += stateLines.size();
            blockCount++;
        }

        // Independent cross-check: the declared counts must equal what was serialized.
        if (stateCount != expectedStates) {
            throw new IllegalStateException("state count mismatch: wrote " + stateCount
                    + " but the registry reports " + expectedStates);
        }

        StringBuilder json = new StringBuilder(blocks.length() + 512);
        json.append("{\"schema\": 2, \"mc\": \"1.8.9\",")
                .append(" \"generator\": \"Kairos BlockDumper (loader-1.8.9, task 1.4)\",")
                .append(" \"about\": \"").append(escape(ABOUT)).append("\",")
                .append(" \"counts\": {\"blocks\": ").append(blockCount)
                .append(", \"states\": ").append(stateCount).append("},")
                .append(" \"blocks\": [\n")
                .append(blocks)
                .append("\n]}\n");
        return new Dump(json.toString(), blockCount, stateCount);
    }

    /**
     * The block-level connection facts, generated with the game's own predicates
     * (BlockFence.java:137-149, BlockWall.java:108-120, BlockPane.java:137-139): the
     * surfaces that decide what a fence/wall/pane connects to in the world.
     */
    private static Connector connector(Block block, ResourceLocation name,
            Map<Material, String> fenceGroups) {
        Material material = block.getMaterial(); // Block.java:409
        Connector facts = new Connector();
        // BlockFence.canConnectTo (BlockFence.java:137-149) / BlockWall.canConnectTo
        // (BlockWall.java:108-120): barrier first, then the opaque-full-non-gourd clause.
        facts.cube = block != Blocks.barrier // BlockFence.java:139-141
                && material.isOpaque() && block.isFullCube() && material != Material.gourd;
        // BlockPane.canPaneConnectToBlock (BlockPane.java:137-139).
        facts.pane = block.isFullBlock() // Block.java:379
                || block == Blocks.glass || block == Blocks.stained_glass
                || block == Blocks.stained_glass_pane || block instanceof BlockPane;
        if (block instanceof BlockFence) {
            facts.kind = "fence";
            facts.group = fenceGroups.get(material); // same Material == same group
        } else if (block instanceof BlockWall) {
            facts.kind = "wall";
            facts.group = name.toString(); // BlockWall.canConnectTo: block == this
        } else if (block instanceof BlockPane) {
            facts.kind = "pane";
        } else if (block instanceof BlockFenceGate) {
            facts.kind = "gate";
        }
        return facts;
    }

    private static String connectorJson(Connector facts) {
        StringBuilder sb = new StringBuilder(96);
        sb.append('{');
        if (facts.kind != null) {
            sb.append("\"kind\": \"").append(facts.kind).append("\", ");
        }
        if (facts.group != null) {
            sb.append("\"group\": \"").append(escape(facts.group)).append("\", ");
        }
        sb.append("\"cube\": ").append(facts.cube)
                .append(", \"pane\": ").append(facts.pane).append('}');
        return sb.toString();
    }

    /** One state JSON object, boxes included. */
    private static String stateJson(Block block, IBlockState state, ProbeWorld probe,
            TreeMap<String, String> props) {
        probe.setProbe(state);

        // Prime the mutable instance bounds exactly like the client render path does
        // (BlockModelRenderer.java:34) -- see the class comment for why -- and then run
        // the real entity collision chain (Block.java:613 -> :620). Air neighbours and a
        // null entity make this the player-relevant lone-block shape.
        block.setBlockBoundsBasedOnState(probe, ORIGIN);                    // Block.java:918
        List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>(4);
        block.addCollisionBoxesToList(probe, ORIGIN, state, MASK, boxes, null); // Block.java:613

        // The pane connection predicate asks the NEIGHBOUR's isSideSolid for the face
        // pointing back (BlockPane.java:162-166); it is state-dependent (slab/stairs/snow/
        // hopper/...), so it is dumped per state. getActualState inside reads the probe,
        // whose only non-air block is this state at the origin.
        int solid = 0;
        for (EnumFacing side : EnumFacing.values()) {       // DOWN,UP,NORTH,SOUTH,WEST,EAST
            if (block.isSideSolid(probe, ORIGIN, side)) {   // Block.java:1262
                solid |= 1 << side.ordinal();
            }
        }

        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"props\": {");
        boolean first = true;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append('"');
        }
        sb.append("}, \"boxes\": [");
        first = true;
        for (AxisAlignedBB box : boxes) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('[').append(box.minX).append(", ").append(box.minY).append(", ")
                    .append(box.minZ).append(", ").append(box.maxX).append(", ")
                    .append(box.maxY).append(", ").append(box.maxZ).append(']');
        }
        sb.append("], \"slipperiness\": ").append(Float.toString(block.slipperiness)) // Block.java:321
                // isLadder(IBlockAccess, BlockPos, EntityLivingBase): Block.java:1250,
                // BlockLadder.java:137, BlockVine.java:366 (all ignore the entity).
                .append(", \"climbable\": ").append(block.isLadder(probe, ORIGIN, null))
                .append(", \"solid\": ").append(solid)
                .append('}');
        return sb.toString();
    }

    /** JSON string escaping, locale-independent (own hex table; no String.format). */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * The value name of a property. The raw types mirror the 1.8.9 signatures
     * ({@code IProperty<T extends Comparable<T>>}, IProperty.java:8/:21).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(IProperty property, Comparable value) {
        return property.getName(value); // IProperty.java:21
    }

    private static String sha256Hex(byte[] data) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to ship SHA-256.
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    private static void chat(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.GRAY + "[Kairos] " + EnumChatFormatting.RESET + message));
    }

    private static void chatRed(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + "[Kairos] " + message));
    }

    private static final class Dump {
        final String json;
        final int blocks;
        final int states;

        Dump(String json, int blocks, int states) {
            this.json = json;
            this.blocks = blocks;
            this.states = states;
        }
    }

    /** Mutable carrier while the block-level connector facts are generated. */
    private static final class Connector {
        String kind;
        String group;
        boolean cube;
        boolean pane;
    }

    /**
     * Minimal {@code World} that returns the state under test at the origin and air
     * everywhere else. {@code World} is abstract with only two abstract methods
     * (World.java:244, :2039), and the constructor used here only stores its arguments
     * (World.java:194-204); the provider is needed for
     * {@code worldBorder = providerIn.getWorldBorder()} (World.java:202,
     * WorldProvider.java:248). The collision chains read {@code getBlockState} (neighbours
     * are air, so lone-block shapes come out) and, for
     * {@code minecraft:piston_extension}, {@code getTileEntity}
     * (BlockPistonMoving.java:126, :139 -- null here, so it falls back to default bounds).
     */
    private static final class ProbeWorld extends World {

        private BlockPos probePos;
        private IBlockState probeState;

        ProbeWorld() {
            // World.java:194; WorldInfo.java:197; WorldSettings.java:40 and the nested
            // GameType enum at WorldSettings.java:131; WorldType.DEFAULT at
            // WorldType.java:37; Profiler has the implicit no-arg constructor.
            super(null,
                    new WorldInfo(new WorldSettings(0L, WorldSettings.GameType.SURVIVAL,
                            false, false, WorldType.DEFAULT), "KairosBlockProbe"),
                    new WorldProviderSurface(), new Profiler(), true);
        }

        void setProbe(IBlockState state) {
            this.probePos = ORIGIN;
            this.probeState = state;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            if (this.probePos != null && this.probePos.equals(pos)) {
                return this.probeState;
            }
            return Blocks.air.getDefaultState(); // Blocks.java:252, BlockAir.java
        }

        @Override
        public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) {
            // World.java:1813 would need a chunk; BlockPistonMoving tolerates null.
            return null;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null; // never reached: nothing in the chain loads chunks
        }

        @Override
        protected int getRenderDistanceChunks() {
            return 0; // never reached
        }
    }
}
