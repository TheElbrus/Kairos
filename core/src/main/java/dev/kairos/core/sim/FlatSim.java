package dev.kairos.core.sim;

import dev.kairos.core.trace.TraceInput;

/**
 * THROWAWAY Phase 0.5 thin slice: a flat-ground player simulator that reproduces the three
 * recorded flat traces (walk / sprint / sprintjump) tick for tick, exactly (ADR 0007).
 *
 * <p><strong>Phase 1 replaces this class with {@code physics.MovementSim}.</strong> Do not
 * grow it: the world model is a single full-block floor with its top surface at
 * {@code y = 65.0} and nothing else, exactly the worlds of
 * {@code data/scenarios/{walk,sprint,sprintjump}_flat_01.json}. Everything here is the
 * flat-ground subset of {@code docs/physics-notes.md}; every rule cites a note section and
 * the {@code mc-src} file:line that note cites. Unsupported inputs throw
 * {@link UnsupportedOperationException} instead of guessing.
 *
 * <p>{@link #step} is in-place and allocation-free on the happy path (AGENTS.md rule 7).
 * All physics state lives in the caller-owned {@link State}.
 */
public final class FlatSim {

    /**
     * Player state between ticks: the recorded fields plus what the rules of
     * {@code docs/physics-notes.md} need to carry across a tick.
     *
     * <p>Yaw is not a field: {@link FlatSim#step} only supports {@code yaw == 0.0f} (and
     * throws otherwise), so {@code sin = 0.0f} / {@code cos = 1.0f} are baked in.
     */
    public static final class State {

        /** Player position, same meaning as the trace's {@code pos} (Entity.posX/Y/Z). */
        public double x;
        public double y;
        public double z;

        /** Player motion, same meaning as the trace's {@code vel} (Entity.motionX/Y/Z). */
        public double vx;
        public double vy;
        public double vz;

        /** End-of-tick flags, same meaning as the trace's {@code onGround}/{@code colH}/{@code colV}. */
        public boolean onGround;
        public boolean sprinting;
        public boolean colH;
        public boolean colV;

        /**
         * The entity's bounding box X/Z extents. They persist across ticks: {@code moveEntity}
         * offsets them by the clamped deltas and only re-derives pos as their midpoint
         * (Entity.java:604-608, 756-760). The Y extents are not carried: posY IS the box's
         * minY every tick end, and maxY never matters in the flat subset.
         */
        public double minX;
        public double maxX;
        public double minZ;
        public double maxZ;

        /** EntityLivingBase.jumpTicks (EntityLivingBase.java:1474-1476, 1519-1524). */
        public int jumpTicks;

        /** EntityPlayerSP.sprintingTicksLeft; 600 while sprinting, counts down (EntityPlayerSP.java:403, 553-557). */
        public int sprintingTicksLeft;

        /** EntityPlayerSP.sprintToggleTimer, the 7-tick double-tap window (EntityPlayerSP.java:559-561, 607-608). */
        public int sprintToggleTimer;

        /**
         * The {@code movementInput.moveForward} left over from the previous tick's input
         * update; the {@code flag2} test reads it BEFORE the current tick's keys
         * (EntityPlayerSP.java:594). For the recorded scenarios this is the previous
         * trace line's {@code in.mf}.
         */
        public float moveForward;

        /**
         * EntityLivingBase.jumpMovementFactor (air acceleration, set by the previous tick's
         * tail; notes section 1.6, EntityPlayer.java:534-537).
         */
        public float jumpMovementFactor;
    }

    /** The floor's top surface: block layer y=64..65 of the flat scenarios (notes section 1.7 step 30). */
    private static final double FLOOR_TOP = 65.0;

    /**
     * Floor block slipperiness: "stone" = 0.6f (notes section 2.1, MCPK "Slipperiness" page).
     * The ONLY hand-typed block property here; Phase 1 loads this from {@code blocks.json}
     * (notes section 2.1, section 4).
     */
    private static final float SLIPPERINESS = 0.6f;

    /** Entity.box width/2 = 0.6f/2.0f as a float, widened where the box math uses doubles (Entity.java:396-398). */
    private static final float WIDTH_HALF = 0.6f / 2.0f;

    /** EntityPlayer.speedInAir (EntityPlayer.java:184). */
    private static final float SPEED_IN_AIR = 0.02f;

    /** Sprint movement-speed modifier amount, 0.3f, operation 2 (EntityLivingBase.java:69, 1143-1152). */
    private static final double SPRINT_MODIFIER = (double) 0.3f;

    private FlatSim() {
    }

    /**
     * Builds the state for a recorded anchor line (tick -1): the settle left the player at
     * rest on the floor with no input and no active jump/sprint timers.
     *
     * @param x/y/z, vx/vy/vz, flags the anchor's recorded {@code pos}, {@code vel} and flags
     */
    public static State initialState(double x, double y, double z,
                                     double vx, double vy, double vz,
                                     boolean onGround, boolean sprinting,
                                     boolean collH, boolean collV) {
        State s = new State();
        s.x = x;
        s.y = y;
        s.z = z;
        s.vx = vx;
        s.vy = vy;
        s.vz = vz;
        s.onGround = onGround;
        s.sprinting = sprinting;
        s.colH = collH;
        s.colV = collV;
        s.jumpTicks = 0;
        s.sprintingTicksLeft = sprinting ? 600 : 0;   // EntityPlayerSP.java:403
        s.sprintToggleTimer = 0;
        s.moveForward = 0.0f;                          // settle ran with zero keys
        s.jumpMovementFactor = SPEED_IN_AIR;           // tail of the last settle tick
        // The scenario loader teleports the player to the start position, which builds the
        // box from pos (Entity.setPosition, Entity.java:392-399); the settle ticks move
        // nothing, so the anchor box is still pos +/-(double)(0.6f/2.0f).
        s.minX = x - (double) WIDTH_HALF;
        s.maxX = x + (double) WIDTH_HALF;
        s.minZ = z - (double) WIDTH_HALF;
        s.maxZ = z + (double) WIDTH_HALF;
        return s;
    }

    /**
     * Advances one game tick, in place. Mirrors the local player's {@code onUpdate} physics
     * for the flat, yaw-0, no-effects, survival case; see {@code docs/physics-notes.md}
     * sections 1.4-1.7 for the full rule list.
     *
     * @throws UnsupportedOperationException on any input the flat subset does not specify
     */
    public static void step(State s, TraceInput in) {
        if (in.yaw != 0.0f) {
            throw new UnsupportedOperationException(
                    "FlatSim only supports yaw = 0.0f (the MathHelper.lookup tables for sin/cos,"
                            + " notes section 2.3, are baked in as sin=0.0f, cos=1.0f); got yaw = "
                            + in.yaw);
        }
        if (in.sneak != 0) {
            throw new UnsupportedOperationException(
                    "FlatSim does not model sneaking: the sneak edge slowdown in Entity.moveEntity"
                            + " (Entity.java:576-593) needs block queries the flat world model does"
                            + " not have");
        }

        // --- EntityPlayerSP.onLivingUpdate, input -> sprint (notes section 1.4, EntityPlayerSP.java:551-618).

        if (s.sprintingTicksLeft > 0) {
            --s.sprintingTicksLeft;                    // EntityPlayerSP.java:553-557
            if (s.sprintingTicksLeft == 0) {
                setSprinting(s, false);
            }
        }
        if (s.sprintToggleTimer > 0) {
            --s.sprintToggleTimer;                     // EntityPlayerSP.java:559-561
        }
        boolean flag2 = s.moveForward >= 0.8f;         // EntityPlayerSP.java:594, BEFORE the input update
        s.moveForward = in.mf;                         // :595 updatePlayerMoveState() -> recorded axes
        float moveStrafe = in.ms;
        // :596-599 item-use scaling: not modelled (the traces never record "using").
        // :601-604 pushOutOfBlocks: not modelled (notes section 4: needs world queries, never seen).
        boolean flag3 = true;                          // :605 food > 6 || allowFlying (assumed; not an input)

        // Sprint start, walking path (:606-611): not sneaking, previous mf < 0.8, current mf >= 0.8.
        if (s.onGround && !flag2 && s.moveForward >= 0.8f && !s.sprinting && flag3) {
            if (s.sprintToggleTimer <= 0 && in.sprint == 0) {
                s.sprintToggleTimer = 7;
            } else {
                setSprinting(s, true);
            }
        }
        // Sprint start, sprint-key path (:613-614).
        if (!s.sprinting && s.moveForward >= 0.8f && flag3 && in.sprint != 0) {
            setSprinting(s, true);
        }
        // Sprint stop (:616-618); collH is the previous tick's moveEntity value.
        if (s.sprinting && (s.moveForward < 0.8f || s.colH || !flag3)) {
            setSprinting(s, false);
        }
        // :619-642 flying / :643-662 horse: not modelled (survival, not riding).

        // --- EntityLivingBase.onLivingUpdate core (notes section 1.5, EntityLivingBase.java:1473-1537).

        if (s.jumpTicks > 0) {
            --s.jumpTicks;                             // :1474-1476
        }
        // :1477-1491 newPosRotationIncrements = 0 and isServerWorld() = true for the local
        // player (notes section 1.3), so neither interpolation nor the 0.98 drag runs.
        if (Math.abs(s.vx) < 0.005) {
            s.vx = 0.0;                                // :1492-1500 momentum threshold
        }
        if (Math.abs(s.vy) < 0.005) {
            s.vy = 0.0;
        }
        if (Math.abs(s.vz) < 0.005) {
            s.vz = 0.0;
        }
        // :1502-1510 isMovementBlocked() is false (survival, alive) -> updateEntityActionState()
        // copies movementInput into the entity (notes section 1.5 step 24).
        boolean isJumping = in.jump != 0;
        if (isJumping) {
            // :1513-1525; no water/lava in the flat world model.
            if (s.onGround && s.jumpTicks == 0) {
                jump(s);
                s.jumpTicks = 10;
            }
        } else {
            s.jumpTicks = 0;
        }
        float strafe = moveStrafe * 0.98f;             // :1527-1530
        float forward = s.moveForward * 0.98f;
        moveEntityWithHeading(s, strafe, forward);     // :1531
        // :1533-1537 collideWithNearbyEntities runs only when !worldObj.isRemote (server).

        // --- EntityPlayer.onLivingUpdate tail (notes section 1.6, EntityPlayer.java:534-538).

        s.jumpMovementFactor = SPEED_IN_AIR;
        if (s.sprinting) {
            s.jumpMovementFactor = (float) ((double) s.jumpMovementFactor + (double) SPEED_IN_AIR * 0.3);
        }
        // :538 setAIMoveSpeed is a no-op for players: getAIMoveSpeed() reads the attribute live.
    }

    /**
     * EntityLivingBase.setSprinting: toggles the flag and the speed attribute modifier
     * (EntityLivingBase.java:1143-1152); EntityPlayerSP adds the 600-tick timer
     * (EntityPlayerSP.java:400-404).
     */
    private static void setSprinting(State s, boolean sprinting) {
        s.sprinting = sprinting;
        s.sprintingTicksLeft = sprinting ? 600 : 0;
    }

    /** EntityLivingBase.jump (EntityLivingBase.java:1222-1234); no Potion.jump effects. */
    private static void jump(State s) {
        s.vy = (double) 0.42f;                          // getJumpUpwardsMotion :1215-1217, :1223
        if (s.sprinting) {
            // Sprint-jump boost :1227-1231, direction (-sin(yaw), +cos(yaw)) = (0, +1) at yaw 0.
            s.vx -= (double) (0.0f * 0.2f);
            s.vz += (double) (1.0f * 0.2f);
        }
    }

    /** EntityPlayer.getAIMoveSpeed: the live movementSpeed attribute (notes section 2.2). */
    private static float aimoveSpeed(State s) {
        double value = (double) 0.1f;                   // base, EntityPlayer.java:214
        if (s.sprinting) {
            value *= 1.0 + SPRINT_MODIFIER;             // operation 2, ModifiableAttributeInstance.java:159-161
        }
        return (float) value;                           // EntityPlayer.java:1437-1440
    }

    /**
     * EntityLivingBase.moveEntityWithHeading, normal ground/air path
     * (EntityLivingBase.java:1250-1286; notes sections 1.7 and 2.1-2.6).
     */
    private static void moveEntityWithHeading(State s, float strafe, float forward) {
        float f4 = 0.91f;
        if (s.onGround) {
            f4 = SLIPPERINESS * 0.91f;                  // :1255-1257
        }
        float f = 0.16277136f / (f4 * f4 * f4);         // :1258
        float f5 = s.onGround ? aimoveSpeed(s) * f : s.jumpMovementFactor;  // :1259
        moveFlying(s, strafe, forward, f5);             // :1260
        f4 = 0.91f;                                     // :1261-1264 recompute, pre-move onGround
        if (s.onGround) {
            f4 = SLIPPERINESS * 0.91f;
        }
        // :1265-1282 ladder: not modelled (no ladders in the flat world model).
        moveEntity(s);                                  // :1279
        // :1283 gravity, collapsed to the normal local-player expression (notes section 1.7 step 37).
        s.vy -= 0.08;
        s.vy *= (double) 0.98f;                         // :1284, widened float 0.9800000190734863
        s.vx *= (double) f4;                            // :1285
        s.vz *= (double) f4;                            // :1286
    }

    /** Entity.moveFlying (Entity.java:976-988; notes section 2.3). */
    private static void moveFlying(State s, float strafe, float forward, float friction) {
        float f = strafe * strafe + forward * forward;
        if (f >= 1.0E-4f) {
            if ((f = (float) Math.sqrt(f)) < 1.0f) {    // MathHelper.sqrt_float, MathHelper.java:40-42
                f = 1.0f;
            }
            f = friction / f;
            // yaw is 0.0f (enforced above): MathHelper.sin -> table[0] = 0.0f,
            // MathHelper.cos -> table[16384] = 1.0f (MathHelper.java:29-38, 496-498; notes section 2.3).
            float f1 = 0.0f;
            float f2 = 1.0f;
            strafe *= f;
            forward *= f;
            s.vx += (double) (strafe * f2 - forward * f1);
            s.vz += (double) (forward * f2 + strafe * f1);
        }
    }

    /**
     * Entity.moveEntity, flat single-floor subset (Entity.java:553-712; notes section 2.8).
     *
     * <p>World model: one floor block box x -2..3, y 64..65, z -2..13 (the scenarios' block
     * region). The X/Z box extents persist across ticks (they are only offset by the moves;
     * {@code resetPositionToBB} just re-derives pos as the midpoint, Entity.java:756-760),
     * so they live in {@link State}.
     */
    private static void moveEntity(State s) {
        double dx = s.vx;
        double dy = s.vy;
        double dz = s.vz;
        double d3 = dx;
        double d4 = dy;
        double d5 = dz;

        // Y axis (AxisAlignedBB.calculateYOffset, AxisAlignedBB.java:132-146, with this =
        // floor box and other = player box): moving down while the feet are at/above the
        // floor face clamps the move to the face. The clamp condition itself implies the
        // floor is in the swept collision list, so no separate list check is needed.
        if (dy < 0.0 && s.y >= FLOOR_TOP) {
            double d = FLOOR_TOP - s.y;
            if (d > dy) {
                dy = d;
            }
        }
        // X/Z axes: after the Y offset, the player's feet are at 65.0 or above, so the floor
        // box fails calculateXOffset's / calculateZOffset's strict face checks
        // (AxisAlignedBB.java:115, 153) and neither dx nor dz is ever clamped. There are no
        // other blocks, so colH stays false and step-up (Entity.java:609-673) never triggers.

        double minY = s.y + dy;                         // Entity.java:599 box.offset(0, y, 0)
        s.minX += dx;                                   // Entity.java:604 box.offset(x, 0, 0)
        s.maxX += dx;
        s.minZ += dz;                                   // Entity.java:608 box.offset(0, 0, z)
        s.maxZ += dz;
        s.x = (s.minX + s.maxX) / 2.0;                  // resetPositionToBB, Entity.java:756-760
        s.y = minY;
        s.z = (s.minZ + s.maxZ) / 2.0;

        s.colH = d3 != dx || d5 != dz;                  // Entity.java:677-680
        s.colV = d4 != dy;
        s.onGround = s.colV && d4 < 0.0;
        // Entity.java:690 updateFallState only tracks fall distance here (no position/motion
        // effect for this trace; Entity.java:828-841).
        if (d4 != dy) {
            s.vy = 0.0;                                 // Entity.java:697-699 -> Block.onLanded, Block.java:1125-1127
        }
        // Entity.java:691-696 motionX/Z zeroing: d3 == dx and d5 == dz, so nothing to zero.
    }
}
