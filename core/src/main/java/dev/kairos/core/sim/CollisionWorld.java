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

        int[] cellStart = new int[cellCount + 1];
        for (int cell = 0; cell < cellCount; cell++) {
            int index = cellState[cell];
            cellStart[cell + 1] = cellStart[cell]
                    + (index < 0 ? 0 : states[index].boxes.size());
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
            for (BlockData.Box bounds : states[index].boxes) {
                int o = box * 6;
                // Block.getCollisionBoundingBox, Block.java:620-622.
                boxBounds[o] = (double) x + bounds.minX;
                boxBounds[o + 1] = (double) y + bounds.minY;
                boxBounds[o + 2] = (double) z + bounds.minZ;
                boxBounds[o + 3] = (double) x + bounds.maxX;
                boxBounds[o + 4] = (double) y + bounds.maxY;
                boxBounds[o + 5] = (double) z + bounds.maxZ;
                box++;
            }
        }
        return new CollisionWorld(minX, minY, minZ, sizeX, sizeY, sizeZ, cellState, states,
                cellStart, boxBounds, boxCount);
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
