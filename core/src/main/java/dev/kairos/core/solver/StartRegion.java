package dev.kairos.core.solver;

import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The top of one 1x1 block as a set of legal standing starts (PLAN.md 2.5).
 *
 * <p>The player is 0.6 blocks wide, so a start centred anywhere in the middle 0.4 by 0.4
 * of the block keeps the whole player box on the block. This class offers the centre and
 * the eight positions inset by {@link #CANDIDATE_INSET} blocks from it, in a fixed order:
 * centre first, then the surrounding positions. The positions are relative to the block
 * centre, so every run-up variant uses the same X/Z candidate set.
 *
 * <p>A candidate becomes a {@link StartState} only after the exact simulator has settled
 * it. The state is created just above the block top with zero motion and no ground flag,
 * then stepped with {@link TraceInput#zero()} until the ground clamp reports
 * {@code onGround} at exactly {@link #surfaceY}. Air, holes, partial support and a block
 * at another height therefore produce no state. No velocity is typed here: the settled
 * vertical motion comes from {@link MovementSim#step}.
 */
public final class StartRegion {

    /** Inset from the block centre that still keeps the full 0.6 player box on top. */
    public static final double CANDIDATE_INSET = 0.2;

    private static final int MAX_SETTLE_TICKS = 8;

    private static final double[][] CANDIDATE_OFFSETS = {
            {0.0, 0.0},
            {-CANDIDATE_INSET, 0.0},
            {CANDIDATE_INSET, 0.0},
            {0.0, -CANDIDATE_INSET},
            {0.0, CANDIDATE_INSET},
            {-CANDIDATE_INSET, -CANDIDATE_INSET},
            {CANDIDATE_INSET, -CANDIDATE_INSET},
            {-CANDIDATE_INSET, CANDIDATE_INSET},
            {CANDIDATE_INSET, CANDIDATE_INSET}
    };

    public final int blockX;
    public final int blockY;
    public final int blockZ;

    /** Exact top of the block, {@code blockY + 1.0}. */
    public final double surfaceY;

    /** The block's horizontal footprint. */
    public final double minX;
    public final double maxX;
    public final double minZ;
    public final double maxZ;

    private StartRegion(int blockX, int blockY, int blockZ) {
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.surfaceY = (double) blockY + 1.0;
        this.minX = (double) blockX;
        this.maxX = (double) blockX + 1.0;
        this.minZ = (double) blockZ;
        this.maxZ = (double) blockZ + 1.0;
    }

    /**
     * Describes the top of one full block.
     *
     * @param blockX block X
     * @param blockY block Y; the standing surface is {@code blockY + 1.0}
     * @param blockZ block Z
     */
    public static StartRegion ofBlockTop(int blockX, int blockY, int blockZ) {
        return new StartRegion(blockX, blockY, blockZ);
    }

    /**
     * Settles every candidate on this block top and returns the supported ones in the
     * documented candidate order.
     *
     * @param world exact collision world; queried, never edited
     * @return an unmodifiable list of settled starts, possibly empty
     */
    public List<StartState> startStates(CollisionWorld world) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (!hasSupportingBlock(world)) {
            return Collections.emptyList();
        }
        List<StartState> states = new ArrayList<StartState>(CANDIDATE_OFFSETS.length);
        for (double[] offset : CANDIDATE_OFFSETS) {
            double x = (double) this.blockX + 0.5 + offset[0];
            double z = (double) this.blockZ + 0.5 + offset[1];
            MovementSim.State state = MovementSim.initialState(
                    x, this.surfaceY, z,
                    0.0, 0.0, 0.0,
                    false, false, false, false);
            if (!settle(state, world)) {
                continue;
            }
            states.add(new StartState(state, 0, this.blockX, this.blockY, this.blockZ,
                    offset[0], offset[1]));
        }
        return Collections.unmodifiableList(states);
    }

    /**
     * Requires the described block cell itself to have a collision box under its centre.
     * This keeps a candidate from settling on a tiny float-width overlap with a
     * neighbouring block when the described block is air.
     */
    private boolean hasSupportingBlock(CollisionWorld world) {
        double x = (double) this.blockX + 0.5;
        double z = (double) this.blockZ + 0.5;
        return !world.isEmpty(
                x - 0.01, (double) this.blockY + 0.01, z - 0.01,
                x + 0.01, this.surfaceY - 0.01, z + 0.01);
    }

    private boolean settle(MovementSim.State state, CollisionWorld world) {
        for (int tick = 0; tick < MAX_SETTLE_TICKS; tick++) {
            MovementSim.step(state, TraceInput.zero(), world);
            if (state.onGround) {
                break;
            }
        }
        return state.onGround
                && Double.compare(state.y, this.surfaceY) == 0
                && Double.compare(state.vx, 0.0) == 0
                && Double.compare(state.vz, 0.0) == 0;
    }

    @Override
    public String toString() {
        return "StartRegion[block=" + this.blockX + "," + this.blockY + "," + this.blockZ
                + ", surfaceY=" + this.surfaceY + "]";
    }
}
