package dev.kairos.core.solver;

import dev.kairos.core.catalog.CatalogEntry;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the catalog's run-up variants on one start block (PLAN.md 2.5).
 *
 * <p>A catalog entry lists distances behind its start block. For every distance this
 * builder settles the corresponding 1x1 block top, replays a deterministic W+sprint
 * run-up through {@link MovementSim#step} until the player has covered exactly that
 * distance, and then shifts the resulting state onto the same start-block candidate.
 * The shift is only a horizontal coordinate change on the same flat surface: it adds no
 * velocity and does not touch the simulated timers, so the returned state carries the
 * exact momentum the simulator produced. No velocity is typed or guessed.
 *
 * <p>Distance 0 returns the standing starts from {@link StartRegion}. Every distance
 * uses the same candidate offsets, and variants are ordered by distance as listed in the
 * catalog, then by the candidate order of {@link StartRegion}. A distance whose run-up
 * block is unsupported or whose run-up cannot stay on the surface is skipped.
 */
public final class RunupStartBuilder {

    private static final int MAX_RUNUP_TICKS = 200;

    private RunupStartBuilder() {
    }

    /**
     * Builds variants along the axis from the start block toward the goal block.
     *
     * @param entry catalog entry with at least one run-up distance
     * @param world exact collision world; queried, never edited
     * @return unmodifiable variants in deterministic order
     * @throws IllegalArgumentException if the start-to-goal direction is diagonal or zero
     */
    public static List<StartState> build(CatalogEntry entry, CollisionWorld world) {
        if (entry == null) {
            throw new NullPointerException("entry");
        }
        int stepX = Integer.compare(entry.goalX, entry.startX);
        int stepZ = Integer.compare(entry.goalZ, entry.startZ);
        if ((stepX == 0) == (stepZ == 0)) {
            throw new IllegalArgumentException("catalog entry \"" + entry.id
                    + "\" needs an axis-aligned start-to-goal direction, got dX="
                    + (entry.goalX - entry.startX) + " dZ="
                    + (entry.goalZ - entry.startZ));
        }
        return build(entry, world, stepX, stepZ);
    }

    /**
     * Builds variants along an explicit unit direction.
     *
     * @param stepX -1, 0 or 1
     * @param stepZ -1, 0 or 1; exactly one of {@code stepX}/{@code stepZ} is non-zero
     */
    public static List<StartState> build(CatalogEntry entry, CollisionWorld world,
            int stepX, int stepZ) {
        if (entry == null) {
            throw new NullPointerException("entry");
        }
        if (world == null) {
            throw new NullPointerException("world");
        }
        if ((stepX == 0) == (stepZ == 0)
                || stepX < -1 || stepX > 1 || stepZ < -1 || stepZ > 1) {
            throw new IllegalArgumentException("run-up direction must be one unit step on"
                    + " exactly one axis, got " + stepX + "," + stepZ);
        }

        float yaw = yawFor(stepX, stepZ);
        StartRegion startRegion = StartRegion.ofBlockTop(
                entry.startX, entry.startY, entry.startZ);
        List<StartState> targets = startRegion.startStates(world);
        List<StartState> variants = new ArrayList<StartState>();

        for (int runupDistance : entry.runup) {
            if (runupDistance == 0) {
                for (StartState target : targets) {
                    variants.add(target);
                }
                continue;
            }
            StartRegion runupRegion = StartRegion.ofBlockTop(
                    entry.startX - stepX * runupDistance,
                    entry.startY,
                    entry.startZ - stepZ * runupDistance);
            List<StartState> runupStarts = runupRegion.startStates(world);
            for (StartState target : targets) {
                StartState runupStart = findByOffset(runupStarts, target);
                if (runupStart == null) {
                    continue;
                }
                StartState variant = replayRunup(runupStart, target, runupDistance,
                        stepX, stepZ, yaw, world);
                if (variant != null) {
                    variants.add(variant);
                }
            }
        }
        return Collections.unmodifiableList(variants);
    }

    /** The yaw that makes a W+sprint input travel along the entry's approach axis. */
    public static float yawFor(CatalogEntry entry) {
        if (entry == null) {
            throw new NullPointerException("entry");
        }
        int stepX = Integer.compare(entry.goalX, entry.startX);
        int stepZ = Integer.compare(entry.goalZ, entry.startZ);
        if ((stepX == 0) == (stepZ == 0)) {
            throw new IllegalArgumentException("catalog entry \"" + entry.id
                    + "\" needs an axis-aligned start-to-goal direction");
        }
        return yawFor(stepX, stepZ);
    }

    /** The yaw that makes a W+sprint input travel along one unit axis step. */
    public static float yawFor(int stepX, int stepZ) {
        if ((stepX == 0) == (stepZ == 0)
                || stepX < -1 || stepX > 1 || stepZ < -1 || stepZ > 1) {
            throw new IllegalArgumentException("run-up direction must be one unit step on"
                    + " exactly one axis, got " + stepX + "," + stepZ);
        }
        if (stepZ > 0) {
            return 0.0f;
        }
        if (stepZ < 0) {
            return 180.0f;
        }
        if (stepX > 0) {
            return -90.0f;
        }
        return 90.0f;
    }

    private static StartState replayRunup(StartState runupStart, StartState target,
            int runupDistance, int stepX, int stepZ, float yaw, CollisionWorld world) {
        MovementSim.State state = copy(runupStart.state);
        double originX = state.x;
        double originZ = state.z;
        TraceInput input = new TraceInput(
                1, 0, 0, 0, 0, 0, 1, yaw, 0.0f, 1.0f, 0.0f);

        for (int tick = 0; tick < MAX_RUNUP_TICKS; tick++) {
            MovementSim.step(state, input, world);
            if (!state.onGround
                    || Double.compare(state.y, target.state.y) != 0) {
                return null;
            }
            double progress = (state.x - originX) * (double) stepX
                    + (state.z - originZ) * (double) stepZ;
            if (progress < 0.0) {
                return null;
            }
            if (progress < (double) runupDistance) {
                continue;
            }
            if (state.vx * (double) stepX + state.vz * (double) stepZ <= 0.0) {
                return null;
            }
            shiftTo(state, target.state.x, target.state.z);
            return new StartState(state, runupDistance,
                    target.blockX, target.blockY, target.blockZ,
                    target.offsetX, target.offsetZ);
        }
        return null;
    }

    private static StartState findByOffset(List<StartState> states, StartState target) {
        for (StartState state : states) {
            if (Double.compare(state.offsetX, target.offsetX) == 0
                    && Double.compare(state.offsetZ, target.offsetZ) == 0) {
                return state;
            }
        }
        return null;
    }

    private static void shiftTo(MovementSim.State state, double x, double z) {
        double dx = x - state.x;
        double dz = z - state.z;
        state.x = x;
        state.z = z;
        state.minX += dx;
        state.maxX += dx;
        state.minZ += dz;
        state.maxZ += dz;
    }

    private static MovementSim.State copy(MovementSim.State source) {
        MovementSim.State copy = new MovementSim.State();
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        copy.vx = source.vx;
        copy.vy = source.vy;
        copy.vz = source.vz;
        copy.onGround = source.onGround;
        copy.sprinting = source.sprinting;
        copy.colH = source.colH;
        copy.colV = source.colV;
        copy.minX = source.minX;
        copy.maxX = source.maxX;
        copy.minZ = source.minZ;
        copy.maxZ = source.maxZ;
        copy.maxY = source.maxY;
        copy.jumpTicks = source.jumpTicks;
        copy.sprintingTicksLeft = source.sprintingTicksLeft;
        copy.sprintToggleTimer = source.sprintToggleTimer;
        copy.moveForward = source.moveForward;
        copy.prevSneak = source.prevSneak;
        copy.jumpMovementFactor = source.jumpMovementFactor;
        return copy;
    }
}
