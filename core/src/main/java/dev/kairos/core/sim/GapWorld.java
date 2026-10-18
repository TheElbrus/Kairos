package dev.kairos.core.sim;

/**
 * THROWAWAY Phase 0.5 world model for task 0.5.2 (Phase 1 replaces it with the real world
 * view): a flat full-block floor whose top surface is {@link #FLOOR_TOP}, with at most one
 * straight gap in +Z.
 *
 * <p>The floor is one block layer {@code y = 64..65}, exactly the scenarios' floor
 * (notes section 1.7 step 30). Columns are indexed by block z position {@code k} (block
 * box z = {@code k .. k+1}):
 * <ul>
 *   <li>near floor: every {@code k < gapStartBlock};</li>
 *   <li>gap: {@code gapStartBlock <= k < gapEndBlock()}, no block;</li>
 *   <li>far floor: {@code gapEndBlock() <= k <= lastFarBlock}.</li>
 * </ul>
 * {@link #NONE} is the flat 0.5.1 world: floor everywhere, no far floor.
 *
 * <p>Overlap semantics are the strict AxisAlignedBB test:
 * a floor block supports the player only while the player's box overlaps the block on both
 * horizontal axes, and for the z axis that is {@code k < maxZ && k+1 > minZ} -- strict
 * inequalities, touching faces do not block (AxisAlignedBB.java:134, the
 * {@code calculateYOffset} early-out; notes section 2.8). The modeled floor spans the
 * scenarios' full x range and the solver's yaw-0 path keeps the player's x box inside it,
 * so x is not part of the overlap test.
 */
public final class GapWorld {

    /** The floor's top surface: block layer y=64..65 of the flat scenarios (notes section 1.7 step 30). */
    public static final double FLOOR_TOP = 65.0;

    /** No gap: the flat 0.5.1 world. {@code gapBlocks == 0} is the sentinel. */
    public static final GapWorld NONE = new GapWorld(0, 0, 0);

    /** First missing floor column (block z index). */
    public final int gapStartBlock;

    /** Number of missing floor columns; {@code 0} means {@link #NONE}. */
    public final int gapBlocks;

    /** Last floor column on the far side of the gap. */
    public final int lastFarBlock;

    private GapWorld(int gapStartBlock, int gapBlocks, int lastFarBlock) {
        this.gapStartBlock = gapStartBlock;
        this.gapBlocks = gapBlocks;
        this.lastFarBlock = lastFarBlock;
    }

    /**
     * Builds a world with one straight gap.
     *
     * @param gapStartBlock first missing floor column (block z index), {@code >= 0}
     * @param gapBlocks     number of missing columns, {@code >= 1}
     * @param lastFarBlock  last far-floor column, {@code >= gapStartBlock + gapBlocks}
     */
    public static GapWorld of(int gapStartBlock, int gapBlocks, int lastFarBlock) {
        if (gapBlocks < 1) {
            throw new IllegalArgumentException(
                    "gapBlocks must be >= 1 (use GapWorld.NONE for a flat world), got " + gapBlocks);
        }
        if (gapStartBlock < 0) {
            throw new IllegalArgumentException("gapStartBlock must be >= 0, got " + gapStartBlock);
        }
        int gapEndBlock = gapStartBlock + gapBlocks;
        if (lastFarBlock < gapEndBlock) {
            throw new IllegalArgumentException(
                    "lastFarBlock must be >= gapStartBlock + gapBlocks (" + gapEndBlock
                            + ") so the far floor exists, got " + lastFarBlock);
        }
        return new GapWorld(gapStartBlock, gapBlocks, lastFarBlock);
    }

    /** @return true unless this is the flat {@link #NONE} world. */
    public boolean isGap() {
        return gapBlocks > 0;
    }

    /** @return the first far-floor column, {@code gapStartBlock + gapBlocks}. */
    public int gapEndBlock() {
        return gapStartBlock + gapBlocks;
    }

    /** @return true iff column {@code k} (box z = k..k+1) is floor. */
    public boolean columnExists(int k) {
        if (gapBlocks == 0) {
            return true;                                // NONE: floor everywhere
        }
        return k < gapStartBlock || (k >= gapEndBlock() && k <= lastFarBlock);
    }

    /**
     * True iff the player box's z extent {@code [minZ, maxZ]} strictly overlaps at least one
     * existing floor column, i.e. the game's collision sweep would find a floor block under
     * the player at this position (AxisAlignedBB.java:134; notes section 2.8).
     */
    public boolean overlapsFloor(double minZ, double maxZ) {
        if (gapBlocks == 0) {
            return true;                                // NONE: the floor spans everything
        }
        for (int k = (int) Math.floor(minZ); k <= (int) Math.ceil(maxZ); k++) {
            if (columnExists(k) && overlapsColumn(k, minZ, maxZ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True iff the box's z extent strictly overlaps at least one far-floor column
     * {@code gapEndBlock() .. lastFarBlock} -- the landing test of the task.
     */
    public boolean overlapsFarFloor(double minZ, double maxZ) {
        if (gapBlocks == 0) {
            return false;                               // NONE: no far side
        }
        for (int k = Math.max(gapEndBlock(), (int) Math.floor(minZ));
             k <= Math.min(lastFarBlock, (int) Math.ceil(maxZ)); k++) {
            if (overlapsColumn(k, minZ, maxZ)) {
                return true;
            }
        }
        return false;
    }

    /** Strict AxisAlignedBB z overlap: {@code other.maxZ > this.minZ && other.minZ < this.maxZ}. */
    private static boolean overlapsColumn(int k, double minZ, double maxZ) {
        return (double) k < maxZ && (double) (k + 1) > minZ;
    }

    @Override
    public String toString() {
        return "GapWorld[gapStartBlock=" + gapStartBlock + ", gapBlocks=" + gapBlocks
                + ", lastFarBlock=" + lastFarBlock + "]";
    }
}
