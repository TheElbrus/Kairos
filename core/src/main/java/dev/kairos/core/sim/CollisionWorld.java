package dev.kairos.core.sim;

import dev.kairos.core.block.BlockData;
import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;

import java.util.Arrays;
import java.util.List;

/**
 * The collision world of one scenario (task 1.2): every placed block's collision boxes
 * from {@code data/1.8.9/blocks.json} laid out on the exact block grid, plus the
 * query that mirrors the game's sweep.
 *
 * <p>Model: a dense cell array over the scenario's block bounds; each cell holds the
 * resolved state's boxes (in dump order) translated to world coordinates exactly the way
 * {@code Block.getCollisionBoundingBox} builds them
 * ({@code (double)pos.getX() + this.minX}, Block.java:620-622). Cells outside the placed
 * regions are air, which is what the game reads for the unused space of a loaded chunk
 * (and the harness places every block the scenarios touch). The query walks x, then z,
 * then y from {@code floor(minY) - 1}: that is {@code World.getCollidingBoundingBoxes}'
 * loop nesting (World.java:1016-1031), and the list ORDER matters because the offset
 * passes stop at the first contact and never look at later boxes.
 *
 * <p>The per-box test is {@code mask.intersectsWith(box)}
 * ({@code Block.addCollisionBoxesToList}, Block.java:613-618), and the offset helpers
 * port {@code AxisAlignedBB.calculateX/Y/ZOffset} (AxisAlignedBB.java:113-165) with
 * {@code this} = the world box and {@code other} = the moving entity box. The
 * entity-vs-entity part of the game's query (World.java:1034-1043) is not modelled:
 * the scenarios contain no other entities.
 *
 * <p>Pure Java, no {@code net.minecraft.*} imports (AGENTS.md rule 1) and no allocation
 * on the query path (rule 7): {@link #query} writes into a preallocated index buffer
 * sized to the world's box count - a query can never match more boxes than the world
 * contains. {@link #of} resolves every region up front and fails with a scenario-level
 * message rather than guessing (no hand-typed shapes, AGENTS.md rule 8).
 *
 * <p><b>Fences, walls and panes (task 1.4).</b> Their collision boxes depend on the
 * neighbours, not only on the state, so {@link #of} recomputes them per cell from the
 * scenario grid: {@code BlockFence.addCollisionBoxesToList} (BlockFence.java:61-94),
 * {@code BlockWall.setBlockBoundsBasedOnState} + {@code getCollisionBoundingBox}
 * (BlockWall.java:67-106) and {@code BlockPane.addCollisionBoxesToList}
 * (BlockPane.java:69-97), with the connection predicates read from the dumped connector
 * facts (block-level kind/group/cube/pane and the per-state {@code isSideSolid} mask).
 * Cells outside the placed regions are air, exactly like the probe the lone-block dump
 * was made with.
 */
public final class CollisionWorld {

    /**
     * Cap on the dense position grid. Scenarios are hand-built sketches; a world larger
     * than this is a mistake, not a use case (the gate's time budget would not survive
     * it anyway).
     */
    private static final int MAX_CELLS = 1 << 21;

    /** Block's field default (Block.java:321): stone and air are 0.6f. */
    private static final float DEFAULT_SLIPPERINESS = 0.6f;

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    /** State index of cell {@code c}; -1 when the cell is unassigned (air). */
    private final int[] cellState;

    /** The resolved region states, indexed by the values in {@link #cellState}. */
    private final BlockData.State[] states;

    /** Box index range of cell {@code c} is {@code [cellStart[c], cellStart[c + 1])}. */
    private final int[] cellStart;

    /** Flat box table, 6 doubles (minX,minY,minZ,maxX,maxY,maxZ) per box, world coords. */
    private final double[] boxBounds;

    /** Query scratch: box indices of the last {@link #query}. */
    private final int[] queryHits;
    private int queryHitCount;

    private CollisionWorld(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
            int[] cellState, BlockData.State[] states, int[] cellStart, double[] boxBounds,
            int boxCount) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cellState = cellState;
        this.states = states;
        this.cellStart = cellStart;
        this.boxBounds = boxBounds;
        this.queryHits = new int[Math.max(1, boxCount)];
    }

    /**
     * Builds the collision world of a scenario from the frozen block dump.
     *
     * <p>Region ids may be short ({@code "stone"}, like the scenario files) or namespaced;
     * props, when present, must name a state exactly as dumped (every property of the
     * state). A region without props is only allowed when its block has exactly one state
     * in the dump - otherwise the choice is ambiguous and throws.
     *
     * @throws IllegalArgumentException naming the region for unknown ids, ambiguous
     *         states, unknown props or an oversized grid
     */
    public static CollisionWorld of(BlockData dump, Scenario scenario) {
        List<ScenarioBlock> regions = scenario.blocks;

        int minX = 0;
        int minY = 0;
        int minZ = 0;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (ScenarioBlock region : regions) {
            minX = Math.min(minX, Math.min(region.fromX, region.toX));
            minY = Math.min(minY, Math.min(region.fromY, region.toY));
            minZ = Math.min(minZ, Math.min(region.fromZ, region.toZ));
            maxX = Math.max(maxX, Math.max(region.fromX, region.toX));
            maxY = Math.max(maxY, Math.max(region.fromY, region.toY));
            maxZ = Math.max(maxZ, Math.max(region.fromZ, region.toZ));
        }
        int sizeX = Math.max(1, maxX - minX + 1);
        int sizeY = Math.max(1, maxY - minY + 1);
        int sizeZ = Math.max(1, maxZ - minZ + 1);
        long cells = (long) sizeX * sizeY * sizeZ;
        if (cells > MAX_CELLS) {
            throw new IllegalArgumentException("scenario \"" + scenario.name + "\" spans "
                    + cells + " block positions; the collision world is a dense grid and"
                    + " supports at most " + MAX_CELLS);
        }

        BlockData.State[] states = new BlockData.State[regions.size()];
        for (int i = 0; i < regions.size(); i++) {
            states[i] = resolveState(dump, regions.get(i), i);
        }

        int cellCount = (int) cells;
        int[] cellState = new int[cellCount];
        Arrays.fill(cellState, -1);
        for (int i = 0; i < regions.size(); i++) {
            ScenarioBlock region = regions.get(i);
            int x0 = Math.min(region.fromX, region.toX);
            int x1 = Math.max(region.fromX, region.toX);
            int y0 = Math.min(region.fromY, region.toY);
            int y1 = Math.max(region.fromY, region.toY);
            int z0 = Math.min(region.fromZ, region.toZ);
            int z1 = Math.max(region.fromZ, region.toZ);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        // Later regions overwrite earlier ones, like the harness places them.
                        cellState[cellIndex(minX, minY, minZ, sizeY, sizeZ, x, y, z)] = i;
                    }
                }
            }
        }

        // Two passes over the same shape writer: count first (dense prefix table), then
        // fill. The scratch is sized from the data: connector shapes are at most two
        // boxes, but non-connector blocks use every dumped box (hopper/cauldron have 5).
        int maxBoxes = 2;
        for (BlockData.State state : states) {
            maxBoxes = Math.max(maxBoxes, state.boxes.size());
        }
        double[] shape = new double[maxBoxes * 6];
        int[] cellStart = new int[cellCount + 1];
        for (int cell = 0; cell < cellCount; cell++) {
            int index = cellState[cell];
            cellStart[cell + 1] = cellStart[cell] + (index < 0 ? 0
                    : shapeBoxes(states[index], cellState, states, minX, minY, minZ,
                            sizeX, sizeY, sizeZ, cell, shape));
        }
        int boxCount = cellStart[cellCount];
        double[] boxBounds = new double[boxCount * 6];
        int box = 0;
        for (int cell = 0; cell < cellCount; cell++) {
            int index = cellState[cell];
            if (index < 0) {
                continue;
            }
            int localZ = cell % sizeZ;
            int rest = cell / sizeZ;
            int localY = rest % sizeY;
            int localX = rest / sizeY;
            int x = minX + localX;
            int y = minY + localY;
            int z = minZ + localZ;
            int count = shapeBoxes(states[index], cellState, states, minX, minY, minZ,
                    sizeX, sizeY, sizeZ, cell, shape);
            for (int s = 0; s < count; s++) {
                int o = box * 6;
                int i = s * 6;
                // Block.getCollisionBoundingBox, Block.java:620-622.
                boxBounds[o] = (double) x + shape[i];
                boxBounds[o + 1] = (double) y + shape[i + 1];
                boxBounds[o + 2] = (double) z + shape[i + 2];
                boxBounds[o + 3] = (double) x + shape[i + 3];
                boxBounds[o + 4] = (double) y + shape[i + 4];
                boxBounds[o + 5] = (double) z + shape[i + 5];
                box++;
            }
        }
        return new CollisionWorld(minX, minY, minZ, sizeX, sizeY, sizeZ, cellState, states,
                cellStart, boxBounds, boxCount);
    }

    /**
     * Writes the cell's collision boxes in block-local coordinates into {@code out}
     * (6 doubles per box) and returns the count. Non-connector blocks copy every one of
     * their dump boxes (hopper/cauldron have 5, so {@code out} is sized from the data);
     * fences, walls and panes are recomputed from the neighbours and never exceed 2.
     */
    private static int shapeBoxes(BlockData.State state, int[] cellState,
            BlockData.State[] states, int minX, int minY, int minZ, int sizeX, int sizeY,
            int sizeZ, int cell, double[] out) {
        String kind = state.connector.kind;
        if (!"fence".equals(kind) && !"wall".equals(kind) && !"pane".equals(kind)) {
            int count = state.boxes.size();
            for (int i = 0; i < count; i++) {
                BlockData.Box b = state.boxes.get(i);
                int o = i * 6;
                out[o] = b.minX;
                out[o + 1] = b.minY;
                out[o + 2] = b.minZ;
                out[o + 3] = b.maxX;
                out[o + 4] = b.maxY;
                out[o + 5] = b.maxZ;
            }
            return count;
        }

        int localZ = cell % sizeZ;
        int rest = cell / sizeZ;
        int x = minX + rest / sizeY;
        int y = minY + rest % sizeY;
        int z = minZ + localZ;
        BlockData.State north = neighborAt(cellState, states, minX, minY, minZ, sizeX,
                sizeY, sizeZ, x, y, z - 1);
        BlockData.State south = neighborAt(cellState, states, minX, minY, minZ, sizeX,
                sizeY, sizeZ, x, y, z + 1);
        BlockData.State west = neighborAt(cellState, states, minX, minY, minZ, sizeX,
                sizeY, sizeZ, x - 1, y, z);
        BlockData.State east = neighborAt(cellState, states, minX, minY, minZ, sizeX,
                sizeY, sizeZ, x + 1, y, z);

        if ("fence".equals(kind)) {
            return fenceBoxes(state, north, south, west, east, out);
        }
        if ("wall".equals(kind)) {
            return wallBoxes(state, north, south, west, east, out);
        }
        return paneBoxes(north, south, west, east, out);
    }

    /**
     * BlockFence.addCollisionBoxesToList (BlockFence.java:61-94). The north/south arm is
     * one box (x 0.375..0.625, z extended by the connections, height 1.5); the west/east
     * arm is the second box (z 0.375..0.625, x extended) and is also the lone-post
     * fallback when neither north nor south connects.
     */
    private static int fenceBoxes(BlockData.State self, BlockData.State north,
            BlockData.State south, BlockData.State west, BlockData.State east,
            double[] out) {
        boolean n = fenceConnects(self, north);          // BlockFence.java:138-141
        boolean s = fenceConnects(self, south);
        boolean w = fenceConnects(self, west);
        boolean e = fenceConnects(self, east);
        float f = 0.375f;                                // BlockFence.java:142-145
        float g = 0.625f;
        float h = 0.375f;
        float i = 0.625f;
        if (n) {
            h = 0.0f;
        }
        if (s) {
            i = 1.0f;
        }
        int count = 0;
        if (n || s) {                                    // BlockFence.java:150-153
            put(out, count++, f, 0.0f, h, g, 1.5f, i);
        }
        h = 0.375f;                                      // BlockFence.java:154-155
        i = 0.625f;
        if (w) {
            f = 0.0f;
        }
        if (e) {
            g = 1.0f;
        }
        if (w || e || !n && !s) {                        // BlockFence.java:160-163
            put(out, count++, f, 0.0f, h, g, 1.5f, i);
        }
        return count;
    }

    /**
     * BlockWall.setBlockBoundsBasedOnState / getCollisionBoundingBox
     * (BlockWall.java:67-106): one box, the straight north-south or east-west run narrows
     * it to 0.3125 and the collision height is forced to 1.5 (BlockWall.java:104).
     */
    private static int wallBoxes(BlockData.State self, BlockData.State north,
            BlockData.State south, BlockData.State west, BlockData.State east,
            double[] out) {
        boolean n = wallConnects(self, north);           // BlockWall.java:109-112
        boolean s = wallConnects(self, south);
        boolean w = wallConnects(self, west);
        boolean e = wallConnects(self, east);
        float f = 0.25f;                                 // BlockWall.java:113-117
        float g = 0.75f;
        float h = 0.25f;
        float i = 0.75f;
        if (n) {
            h = 0.0f;
        }
        if (s) {
            i = 1.0f;
        }
        if (w) {
            f = 0.0f;
        }
        if (e) {
            g = 1.0f;
        }
        if (n && s && !w && !e) {                        // BlockWall.java:126-129
            f = 0.3125f;
            g = 0.6875f;
        } else if (!n && !s && w && e) {                 // BlockWall.java:130-133
            h = 0.3125f;
            i = 0.6875f;
        }
        put(out, 0, f, 0.0f, h, g, 1.5f, i);             // BlockWall.java:103-104
        return 1;
    }

    /**
     * BlockPane.addCollisionBoxesToList (BlockPane.java:69-97): an x-axis plate
     * (z 0.4375..0.5625) and a z-axis plate (x 0.4375..0.5625), each a half plate when
     * exactly one horizontal side connects and no perpendicular plate is needed.
     */
    private static int paneBoxes(BlockData.State north, BlockData.State south,
            BlockData.State west, BlockData.State east, double[] out) {
        // BlockPane.canPaneConnectTo (BlockPane.java:162-166): the neighbour's pane group
        // or its isSideSolid face pointing back at us.
        boolean n = paneConnects(north, 3);              // our north -> its SOUTH face
        boolean s = paneConnects(south, 2);              // our south -> its NORTH face
        boolean w = paneConnects(west, 5);               // our west -> its EAST face
        boolean e = paneConnects(east, 4);               // our east -> its WEST face
        int count = 0;
        if ((!w || !e) && (w || e || n || s)) {          // BlockPane.java:74-84
            if (w) {
                put(out, count++, 0.0f, 0.0f, 0.4375f, 0.5f, 1.0f, 0.5625f);
            } else if (e) {
                put(out, count++, 0.5f, 0.0f, 0.4375f, 1.0f, 1.0f, 0.5625f);
            }
        } else {                                         // BlockPane.java:85-87
            put(out, count++, 0.0f, 0.0f, 0.4375f, 1.0f, 1.0f, 0.5625f);
        }
        if ((!n || !s) && (w || e || n || s)) {          // BlockPane.java:88-97
            if (n) {
                put(out, count++, 0.4375f, 0.0f, 0.0f, 0.5625f, 1.0f, 0.5f);
            } else if (s) {
                put(out, count++, 0.4375f, 0.0f, 0.5f, 0.5625f, 1.0f, 1.0f);
            }
        } else {
            put(out, count++, 0.4375f, 0.0f, 0.0f, 0.5625f, 1.0f, 1.0f);
        }
        return count;
    }

    /**
     * BlockFence.canConnectTo (BlockFence.java:137-149): barrier is never a connection
     * (already folded out of {@code connector.cube}), a fence of the same Material
     * connects, a gate connects, and an opaque full cube that is not a gourd connects.
     */
    private static boolean fenceConnects(BlockData.State self, BlockData.State neighbour) {
        if (neighbour == null) {
            return false;
        }
        String kind = neighbour.connector.kind;
        if ("fence".equals(kind)) {
            return self.connector.group.equals(neighbour.connector.group);
        }
        if ("gate".equals(kind)) {
            return true;
        }
        return neighbour.connector.cube;
    }

    /**
     * BlockWall.canConnectTo (BlockWall.java:108-120): the same wall Block instance (the
     * same dump name), any gate, or an opaque full cube that is not a gourd.
     */
    private static boolean wallConnects(BlockData.State self, BlockData.State neighbour) {
        if (neighbour == null) {
            return false;
        }
        String kind = neighbour.connector.kind;
        if ("wall".equals(kind)) {
            return self.connector.group.equals(neighbour.connector.group);
        }
        if ("gate".equals(kind)) {
            return true;
        }
        return neighbour.connector.cube;
    }

    /** BlockPane.canPaneConnectTo: pane group or isSideSolid of the face toward us. */
    private static boolean paneConnects(BlockData.State neighbour, int faceBit) {
        return neighbour != null && (neighbour.connector.pane
                || (neighbour.solidSides & (1 << faceBit)) != 0);
    }

    private static void put(double[] out, int box, float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ) {
        int o = box * 6;
        out[o] = minX;
        out[o + 1] = minY;
        out[o + 2] = minZ;
        out[o + 3] = maxX;
        out[o + 4] = maxY;
        out[o + 5] = maxZ;
    }

    /** The state at a cell, or {@code null} for unassigned cells and outside the grid. */
    private static BlockData.State neighborAt(int[] cellState, BlockData.State[] states,
            int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
            int x, int y, int z) {
        if (x < minX || x >= minX + sizeX || y < minY || y >= minY + sizeY
                || z < minZ || z >= minZ + sizeZ) {
            return null;
        }
        int index = cellState[cellIndex(minX, minY, minZ, sizeY, sizeZ, x, y, z)];
        return index < 0 ? null : states[index];
    }

    /**
     * {@code EntityLivingBase.isOnLadder} (EntityLivingBase.java:878-883): the block at
     * the FEET cell {@code floor(posX), floor(box.minY), floor(posZ)} -- no "- 1" here,
     * unlike the slipperiness lookup. Outside the grid and unassigned cells are air,
     * which is never climbable. O(1), no allocation.
     */
    public boolean climbableAt(double posX, double feetY, double posZ) {
        int x = floor(posX);
        int y = floor(feetY);
        int z = floor(posZ);
        if (x < this.minX || x >= this.minX + this.sizeX
                || y < this.minY || y >= this.minY + this.sizeY
                || z < this.minZ || z >= this.minZ + this.sizeZ) {
            return false;
        }
        int index = this.cellState[cellIndex(x, y, z)];
        return index >= 0 && this.states[index].climbable;
    }

    /**
     * {@code Block.slipperiness} of the block under the feet, read exactly where
     * {@code EntityLivingBase.moveEntityWithHeading} reads it (EntityLivingBase.java:
     * 1254-1264): the block at {@code floor(posX), floor(box.minY) - 1, floor(posZ)}.
     * The entity box's minY is the state's feet y, so the caller passes the entity
     * position. Unassigned cells and positions outside the grid are air, whose
     * slipperiness is Block's 0.6f default (Block.java:321). O(1), no allocation.
     */
    public float slipperinessAt(double posX, double feetY, double posZ) {
        int x = floor(posX);
        int y = floor(feetY) - 1;
        int z = floor(posZ);
        if (x < this.minX || x >= this.minX + this.sizeX
                || y < this.minY || y >= this.minY + this.sizeY
                || z < this.minZ || z >= this.minZ + this.sizeZ) {
            return DEFAULT_SLIPPERINESS;
        }
        int index = this.cellState[cellIndex(x, y, z)];
        return index < 0 ? DEFAULT_SLIPPERINESS : this.states[index].slipperiness;
    }

    private int cellIndex(int x, int y, int z) {
        return ((x - this.minX) * this.sizeY + (y - this.minY)) * this.sizeZ
                + (z - this.minZ);
    }

    private static int cellIndex(int minX, int minY, int minZ, int sizeY, int sizeZ,
            int x, int y, int z) {
        return ((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ);
    }

    private static BlockData.State resolveState(BlockData dump, ScenarioBlock region,
            int index) {
        String id = region.id.indexOf(':') >= 0 ? region.id : "minecraft:" + region.id;
        List<BlockData.State> candidates = dump.states(id);
        if (candidates == null) {
            throw new IllegalArgumentException("blocks[" + index + "].id: \"" + region.id
                    + "\" is not in the block dump");
        }
        if (region.props.isEmpty()) {
            if (candidates.size() != 1) {
                throw new IllegalArgumentException("blocks[" + index + "] (\"" + region.id
                        + "\"): this block has " + candidates.size() + " states; add a"
                        + " \"props\" object naming one state's full property set"
                        + " (e.g. {\"variant\": \"stone\"})");
            }
            return candidates.get(0);
        }
        BlockData.State state = dump.state(id, region.props);
        if (state == null) {
            throw new IllegalArgumentException("blocks[" + index + "] (\"" + region.id
                    + "\"): no state matches props " + region.props);
        }
        return state;
    }

    /**
     * World.getCollidingBoundingBoxes' block sweep (World.java:1003-1046):
     * {@code x in [floor(minX), floor(maxX + 1))} outer, {@code z} middle,
     * {@code y in [floor(minY) - 1, floor(maxY + 1))} inner; each cell's boxes are
     * appended when {@code mask.intersectsWith(box)} (Block.java:615). Out-of-bounds
     * cells are air, like an unplaced region. Results stay in {@link #queryHits} until
     * the next query.
     */
    public void query(double maskMinX, double maskMinY, double maskMinZ,
            double maskMaxX, double maskMaxY, double maskMaxZ) {
        this.queryHitCount = 0;
        int toX = floor(maskMaxX + 1.0);
        int toY = floor(maskMaxY + 1.0);
        int toZ = floor(maskMaxZ + 1.0);
        int fromY = floor(maskMinY) - 1;                 // World.java:1019 (k - 1)
        for (int x = floor(maskMinX); x < toX; x++) {
            if (x < this.minX || x >= this.minX + this.sizeX) {
                continue;                                // World.java:1018 isBlockLoaded
            }
            for (int z = floor(maskMinZ); z < toZ; z++) {
                if (z < this.minZ || z >= this.minZ + this.sizeZ) {
                    continue;
                }
                int column = (x - this.minX) * this.sizeY * this.sizeZ + (z - this.minZ);
                for (int y = fromY; y < toY; y++) {
                    if (y < this.minY || y >= this.minY + this.sizeY) {
                        continue;
                    }
                    int cell = column + (y - this.minY) * this.sizeZ;
                    for (int b = this.cellStart[cell]; b < this.cellStart[cell + 1]; b++) {
                        if (intersects(maskMinX, maskMinY, maskMinZ, maskMaxX, maskMaxY,
                                maskMaxZ, b)) {
                            this.queryHits[this.queryHitCount++] = b;
                        }
                    }
                }
            }
        }
    }

    /** True when {@link #query} with the same mask would have found nothing. */
    public boolean isEmpty(double maskMinX, double maskMinY, double maskMinZ,
            double maskMaxX, double maskMaxY, double maskMaxZ) {
        query(maskMinX, maskMinY, maskMinZ, maskMaxX, maskMaxY, maskMaxZ);
        return this.queryHitCount == 0;
    }

    /** Number of boxes the last {@link #query} matched; the results are indices. */
    public int queryCount() {
        return this.queryHitCount;
    }

    /** Box index of hit {@code hit} of the last {@link #query}. */
    public int queryBox(int hit) {
        return this.queryHits[hit];
    }

    /** Writes box {@code box}'s world bounds (minX,minY,minZ,maxX,maxY,maxZ) into out. */
    public void boxBounds(int box, double[] out) {
        System.arraycopy(this.boxBounds, box * 6, out, 0, 6);
    }

    /**
     * AxisAlignedBB.calculateYOffset with {@code this} = the world box {@code box} and
     * {@code other} = the moving box (AxisAlignedBB.java:132-146).
     */
    public double yOffset(int box, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double offsetY) {
        int o = box * 6;
        if (maxX <= this.boxBounds[o] || minX >= this.boxBounds[o + 3]
                || maxZ <= this.boxBounds[o + 2] || minZ >= this.boxBounds[o + 5]) {
            return offsetY;
        }
        if (offsetY > 0.0 && maxY <= this.boxBounds[o + 1]) {
            double d = this.boxBounds[o + 1] - maxY;
            if (d < offsetY) {
                offsetY = d;
            }
        } else if (offsetY < 0.0 && minY >= this.boxBounds[o + 4]) {
            double d = this.boxBounds[o + 4] - minY;
            if (d > offsetY) {
                offsetY = d;
            }
        }
        return offsetY;
    }

    /**
     * AxisAlignedBB.calculateXOffset with {@code this} = the world box {@code box} and
     * {@code other} = the moving box (AxisAlignedBB.java:113-127).
     */
    public double xOffset(int box, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double offsetX) {
        int o = box * 6;
        if (maxY <= this.boxBounds[o + 1] || minY >= this.boxBounds[o + 4]
                || maxZ <= this.boxBounds[o + 2] || minZ >= this.boxBounds[o + 5]) {
            return offsetX;
        }
        if (offsetX > 0.0 && maxX <= this.boxBounds[o]) {
            double d = this.boxBounds[o] - maxX;
            if (d < offsetX) {
                offsetX = d;
            }
        } else if (offsetX < 0.0 && minX >= this.boxBounds[o + 3]) {
            double d = this.boxBounds[o + 3] - minX;
            if (d > offsetX) {
                offsetX = d;
            }
        }
        return offsetX;
    }

    /**
     * AxisAlignedBB.calculateZOffset with {@code this} = the world box {@code box} and
     * {@code other} = the moving box (AxisAlignedBB.java:151-165).
     */
    public double zOffset(int box, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double offsetZ) {
        int o = box * 6;
        if (maxX <= this.boxBounds[o] || minX >= this.boxBounds[o + 3]
                || maxY <= this.boxBounds[o + 1] || minY >= this.boxBounds[o + 4]) {
            return offsetZ;
        }
        if (offsetZ > 0.0 && maxZ <= this.boxBounds[o + 2]) {
            double d = this.boxBounds[o + 2] - maxZ;
            if (d < offsetZ) {
                offsetZ = d;
            }
        } else if (offsetZ < 0.0 && minZ >= this.boxBounds[o + 5]) {
            double d = this.boxBounds[o + 5] - minZ;
            if (d > offsetZ) {
                offsetZ = d;
            }
        }
        return offsetZ;
    }

    /**
     * mask.intersectsWith(world box) (AxisAlignedBB.java:170-178; Block.java:615).
     */
    private boolean intersects(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, int box) {
        int o = box * 6;
        if (this.boxBounds[o + 3] <= minX || this.boxBounds[o] >= maxX) {
            return false;
        }
        if (this.boxBounds[o + 4] <= minY || this.boxBounds[o + 1] >= maxY) {
            return false;
        }
        return this.boxBounds[o + 5] > minZ && this.boxBounds[o + 2] < maxZ;
    }

    /** MathHelper.floor_double (MathHelper.java:67-70). */
    private static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }
}
