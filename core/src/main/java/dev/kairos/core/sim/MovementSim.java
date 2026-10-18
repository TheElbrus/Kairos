package dev.kairos.core.sim;

import dev.kairos.core.trace.TraceInput;

/**
 * Phase 1 simulator (task 1.1): the local player's flat-ground movement tick - walk,
 * sprint, jump, air acceleration, drag and the 0.005 momentum threshold.
 *
 * <p>This is the rewrite the PLAN.md 0.5 rule asks for ({@code FlatSim} and
 * {@code GapWorld} stay untouched and frozen; the golden traces are replayed through
 * <em>this</em> class). One tick is laid out in the order of {@code docs/physics-notes.md}
 * sections 1.4-1.7, which mirrors the game:
 * EntityPlayerSP.onLivingUpdate (input -> sprint state) -> EntityLivingBase.onLivingUpdate
 * (jump cooldown, momentum threshold, jump) -> moveEntityWithHeading (acceleration,
 * moveEntity, gravity, drag) -> EntityPlayer.onLivingUpdate tail (next tick's air
 * acceleration). Every rule below cites the note section and the {@code mc-src}
 * file:line that note cites.
 *
 * <p><b>Scope of task 1.1.</b> Only the flat, yaw-0 world: one stone floor layer
 * ({@code y 64..65}) that extends everywhere, with W/sprint/jump inputs. Everything else
 * throws {@link UnsupportedOperationException} instead of guessing: strafe and non-zero
 * yaw (tasks 1.2/1.6), sneaking (1.2), real block shapes and slipperiness from
 * {@code blocks.json} (1.2/1.3), ladders/water/lava/flying (1.4), the full sprint-state
 * traces (1.5). The floor top ({@value #FLOOR_TOP}) and stone slipperiness
 * ({@value #SLIPPERINESS}f) are the only hand-typed block properties, exactly like
 * {@code FlatSim}; task 1.3 replaces them with the dumped block data.
 *
 * <p><b>Purity and allocation.</b> {@link #step(State, TraceInput)} reads and writes only
 * the caller-owned {@link State}: same state + same input gives the same result, always
 * (AGENTS.md rule 2), and there is no allocation on the tick path (rule 7). The public
 * contract is the mutable-state form; solvers copy states when they need branching.
 */
public final class MovementSim {

    /**
     * Floor block slipperiness: stone = 0.6f (notes section 2.1, MCPK "Slipperiness"
     * page). Hand-typed ONLY until task 1.3 loads it from {@code blocks.json}; the flat
     * goldens all run on stone.
     */
    private static final float SLIPPERINESS = 0.6f;

    /**
     * The 1.1 world model: a single full-block stone layer {@code y 64..65}, its top
     * surface at y=65.0, everywhere (the worlds of the {@code *_flat_01} scenarios).
     */
    public static final double FLOOR_TOP = 65.0;

    /** Entity width 0.6f, halved as a float (Entity.setPosition, Entity.java:392-399). */
    private static final float WIDTH_HALF = 0.6f / 2.0f;

    /** EntityPlayer.speedInAir, 0.02f (EntityPlayer.java:184). */
    private static final float SPEED_IN_AIR = 0.02f;

    /** Sprint movement-speed modifier amount 0.3f, operation 2 (EntityLivingBase.java:69, 1143-1152). */
    private static final double SPRINT_MODIFIER = (double) 0.3f;

    /**
     * Per-tick player state. Same fields as {@code FlatSim.State} (that class stays
     * frozen), grouped by what they are: trace-recorded values, the entity box, and the
     * game's cross-tick timers/fields that the notes sections 1.4-1.7 require.
     */
    public static final class State {

        /** Position, same meaning as the trace's {@code pos} (Entity.posX/Y/Z). */
        public double x;
        public double y;
        public double z;

        /** Motion, same meaning as the trace's {@code vel} (Entity.motionX/Y/Z). */
        public double vx;
        public double vy;
        public double vz;

        /** End-of-tick flags, same meaning as the trace's {@code onGround}/{@code colH}/{@code colV}. */
        public boolean onGround;
        public boolean sprinting;
        public boolean colH;
        public boolean colV;

        /**
         * The entity box's X/Z extents. They persist across ticks: {@code moveEntity}
         * offsets them and re-derives the position as their midpoint
         * (Entity.java:604-608, 756-760). Y needs no extents: end-of-tick posY IS the
         * box's minY, and maxY never matters in the flat subset.
         */
        public double minX;
        public double maxX;
        public double minZ;
        public double maxZ;

        /** EntityLivingBase.jumpTicks, the 10-tick autojump cooldown (EntityLivingBase.java:1474-1476, 1519-1524). */
        public int jumpTicks;

        /** EntityPlayerSP.sprintingTicksLeft; 600 on sprint start, counts down (EntityPlayerSP.java:403, 553-557). */
        public int sprintingTicksLeft;

        /** EntityPlayerSP.sprintToggleTimer, the 7-tick double-tap window (EntityPlayerSP.java:559-561, 607-608). */
        public int sprintToggleTimer;

        /**
         * The previous tick's {@code movementInput.moveForward}; the sprint-start test
         * reads it BEFORE the current keys are applied (EntityPlayerSP.java:594).
         */
        public float moveForward;

        /** EntityLivingBase.jumpMovementFactor, the air acceleration set by the previous tick (notes 1.6). */
        public float jumpMovementFactor;
    }

    private MovementSim() {
    }

    /**
     * State matching a recorded {@code tick:-1} anchor line: the settle left the player at
     * rest on the floor with no keys, so the timers are idle, {@code moveForward} is 0 and
     * the last tail already ran with the walk air factor. The box is built from the
     * position (the harness teleports the player; Entity.setPosition, Entity.java:392-399)
     * and the settle ticks move nothing.
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
        s.sprintingTicksLeft = sprinting ? 600 : 0;      // EntityPlayerSP.java:403
        s.sprintToggleTimer = 0;
        s.moveForward = 0.0f;                            // the settle ran with zero keys
        s.jumpMovementFactor = SPEED_IN_AIR;             // tail of the last settle tick
        s.minX = x - (double) WIDTH_HALF;
        s.maxX = x + (double) WIDTH_HALF;
        s.minZ = z - (double) WIDTH_HALF;
        s.maxZ = z + (double) WIDTH_HALF;
        return s;
    }

    /**
     * Advances one game tick, in place.
     *
     * @throws UnsupportedOperationException for any input outside the 1.1 scope (see the
     *         class comment); the message names the task that will bring it
     */
    public static void step(State s, TraceInput in) {
        if (in.yaw != 0.0f) {
            throw new UnsupportedOperationException(
                    "task 1.1 supports yaw = 0.0f only; non-axis yaw needs the real"
                            + " MathHelper sine/cosine tables (notes section 2.3, task 1.6), got yaw = "
                            + in.yaw);
        }
        if (in.ms != 0.0f) {
            throw new UnsupportedOperationException(
                    "task 1.1 supports W-only movement (no S/A/D strafe), got ms = " + in.ms);
        }
        if (in.sneak != 0) {
            throw new UnsupportedOperationException(
                    "sneaking is out of scope: Entity.moveEntity's sneak edge slowdown"
                            + " (Entity.java:576-593) needs block queries (task 1.2)");
        }

        // --- EntityPlayerSP.onLivingUpdate: input -> sprint state
        //     (notes section 1.4, EntityPlayerSP.java:551-618).
        updateSprintState(s, in);

        // --- EntityLivingBase.onLivingUpdate: the core
        //     (notes section 1.5, EntityLivingBase.java:1473-1537).

        if (s.jumpTicks > 0) {
            --s.jumpTicks;                               // :1474-1476
        }
        // :1477-1491 newPosRotationIncrements stays 0 and the local player isServerWorld()
        // (notes 1.3): neither interpolation nor the remote-entity 0.98 drag runs.
        applyMomentumThreshold(s);                       // :1492-1500
        // :1502-1510 isMovementBlocked() is false (survival, alive), so
        // updateEntityActionState() copies the input into the entity (notes 1.5 step 24).
        if (in.jump != 0) {                              // :1513-1525; no water/lava in 1.1
            if (s.onGround && s.jumpTicks == 0) {
                jump(s);
                s.jumpTicks = 10;                        // :1519-1521
            }
        } else {
            s.jumpTicks = 0;                             // :1523-1524
        }
        float strafe = 0.0f;                             // in.ms == 0 enforced above
        float forward = s.moveForward * 0.98f;           // :1527-1530
        moveEntityWithHeading(s, strafe, forward);       // :1531

        // --- EntityPlayer.onLivingUpdate tail
        //     (notes section 1.6, EntityPlayer.java:534-538): next tick's air acceleration.
        s.jumpMovementFactor = SPEED_IN_AIR;
        if (s.sprinting) {
            s.jumpMovementFactor = (float) ((double) s.jumpMovementFactor + (double) SPEED_IN_AIR * 0.3);
        }
        // :538 setAIMoveSpeed is a no-op for players: getAIMoveSpeed() reads the attribute
        // live, which aimoveSpeed() below mirrors.
    }

    /**
     * The sprint decision block of EntityPlayerSP.onLivingUpdate
     * (EntityPlayerSP.java:553-618; notes section 1.4 steps 13-19).
     */
    private static void updateSprintState(State s, TraceInput in) {
        if (s.sprintingTicksLeft > 0) {                  // :553-557
            --s.sprintingTicksLeft;
            if (s.sprintingTicksLeft == 0) {
                setSprinting(s, false);
            }
        }
        if (s.sprintToggleTimer > 0) {                   // :559-561
            --s.sprintToggleTimer;
        }
        boolean wasForward = s.moveForward >= 0.8f;      // :594, BEFORE the keys are read
        s.moveForward = in.mf;                           // :595 (recorded axis, notes 1.4)
        // :596-599 item-use axis scaling and :601-604 pushOutOfBlocks are not modelled:
        // neither is recorded in the traces (notes section 4 lists pushOutOfBlocks).
        boolean foodOk = true;                           // :605 food > 6 || allowFlying (constant in the harness)
        if (s.onGround && !wasForward && s.moveForward >= 0.8f && !s.sprinting && foodOk) {
            if (s.sprintToggleTimer <= 0 && in.sprint == 0) {
                s.sprintToggleTimer = 7;                 // :606-608 double-tap window
            } else {
                setSprinting(s, true);                   // :609-611
            }
        }
        if (!s.sprinting && s.moveForward >= 0.8f && foodOk && in.sprint != 0) {
            setSprinting(s, true);                       // :613-614, the held sprint key
        }
        if (s.sprinting && (s.moveForward < 0.8f || s.colH || !foodOk)) {
            setSprinting(s, false);                      // :616-618; colH is the PREVIOUS tick's value
        }
        // :619-642 flying and :643-662 riding are not modelled (survival on foot).
    }

    /**
     * EntityLivingBase.setSprinting: toggles the flag and the speed modifier
     * (EntityLivingBase.java:1143-1152); EntityPlayerSP adds the 600-tick timer
     * (EntityPlayerSP.java:400-404).
     */
    private static void setSprinting(State s, boolean sprinting) {
        s.sprinting = sprinting;
        s.sprintingTicksLeft = sprinting ? 600 : 0;
    }

    /**
     * The 0.005 momentum threshold (EntityLivingBase.java:1492-1500; notes section 2.7,
     * MCPK "Momentum Threshold"): a component with |value| &lt; 0.005 is zeroed at the
     * START of the tick, before jump and movement. This is what truncates the jump apex.
     */
    private static void applyMomentumThreshold(State s) {
        if (Math.abs(s.vx) < 0.005) {
            s.vx = 0.0;
        }
        if (Math.abs(s.vy) < 0.005) {
            s.vy = 0.0;
        }
        if (Math.abs(s.vz) < 0.005) {
            s.vz = 0.0;
        }
    }

    /** EntityLivingBase.jump (EntityLivingBase.java:1222-1234); no Potion.jump effects. */
    private static void jump(State s) {
        s.vy = (double) 0.42f;                           // getJumpUpwardsMotion :1215-1217, :1223
        if (s.sprinting) {
            // Sprint boost :1227-1231 along the facing heading (-sin, +cos); yaw 0 -> +Z only.
            s.vz += (double) (1.0f * 0.2f);
        }
    }

    /**
     * EntityPlayer.getAIMoveSpeed: the live movementSpeed attribute (EntityPlayer.java:1437-1440;
     * base 0.1f at :214), with the sprint modifier applied as operation 2
     * (ModifiableAttributeInstance.java:150-163).
     */
    private static float aimoveSpeed(State s) {
        double value = (double) 0.1f;
        if (s.sprinting) {
            value *= 1.0 + SPRINT_MODIFIER;
        }
        return (float) value;
    }

    /**
     * EntityLivingBase.moveEntityWithHeading, normal ground/air path
     * (EntityLivingBase.java:1250-1286; notes sections 1.7 and 2.1-2.6). The
     * ladder/water/lava branches (:1287-1323) belong to task 1.4.
     */
    private static void moveEntityWithHeading(State s, float strafe, float forward) {
        // f4 = block.slipperiness * 0.91f from the block under the feet (:1254-1257);
        // the flat world is stone everywhere.
        float f4 = 0.91f;
        if (s.onGround) {
            f4 = SLIPPERINESS * 0.91f;
        }
        float f = 0.16277136f / (f4 * f4 * f4);          // :1258
        float f5 = s.onGround ? aimoveSpeed(s) * f : s.jumpMovementFactor;  // :1259
        moveFlying(s, strafe, forward, f5);              // :1260, notes section 2.3
        f4 = 0.91f;                                      // :1261-1264 recompute
        if (s.onGround) {                                // (still the PRE-move onGround)
            f4 = SLIPPERINESS * 0.91f;
        }
        moveEntity(s);                                   // :1279, notes section 2.8
        s.vy -= 0.08;                                    // :1283 gravity
        s.vy *= (double) 0.98f;                          // :1284, the widened float 0.9800000190734863
        s.vx *= (double) f4;                             // :1285
        s.vz *= (double) f4;                             // :1286
    }

    /**
     * Entity.moveFlying (Entity.java:976-988; notes section 2.3). Yaw is 0.0f (enforced),
     * so MathHelper's table (MathHelper.java:29-38, 496-498) gives sin = 0.0f, cos = 1.0f.
     */
    private static void moveFlying(State s, float strafe, float forward, float friction) {
        float len2 = strafe * strafe + forward * forward;
        if (len2 >= 1.0E-4f) {
            float len = (float) Math.sqrt(len2);         // MathHelper.sqrt_float, MathHelper.java:40-42
            if (len < 1.0f) {
                len = 1.0f;
            }
            float mul = friction / len;
            strafe *= mul;
            forward *= mul;
            s.vx += (double) (strafe * 1.0f - forward * 0.0f);
            s.vz += (double) (forward * 1.0f + strafe * 0.0f);
        }
    }

    /**
     * Entity.moveEntity, flat full-floor subset (Entity.java:553-712; notes section 2.8).
     *
     * <p>The 1.1 world is a full stone layer with its top at {@link #FLOOR_TOP}: the Y
     * clamp is the distance to the floor face while the feet are at or above it
     * (AxisAlignedBB.calculateYOffset, AxisAlignedBB.java:132-146), and the floor's side
     * faces and the step-up (:609-673) are unreachable with feet at/above 65.0. The X/Z
     * box extents persist across ticks and the position is their midpoint
     * (Entity.java:604-608, 756-760).
     */
    private static void moveEntity(State s) {
        double dx = s.vx;
        double dy = s.vy;
        double dz = s.vz;
        double d3 = dx;
        double d4 = dy;
        double d5 = dz;

        if (dy < 0.0 && s.y >= FLOOR_TOP) {              // Entity.java:595-600, Y axis first
            double d = FLOOR_TOP - s.y;
            if (d > dy) {
                dy = d;                                  // clamped to the floor face
            }
        }
        // X (Entity.java:601-604) and Z (:605-608): nothing clamps them in this world
        // (the floor fails the offset methods' Y overlap tests, AxisAlignedBB.java:115, 153).

        double minY = s.y + dy;
        s.minX += dx;
        s.maxX += dx;
        s.minZ += dz;
        s.maxZ += dz;
        s.x = (s.minX + s.maxX) / 2.0;                   // resetPositionToBB, Entity.java:756-760
        s.y = minY;
        s.z = (s.minZ + s.maxZ) / 2.0;

        s.colH = d3 != dx || d5 != dz;                   // Entity.java:677-680
        s.colV = d4 != dy;
        s.onGround = s.colV && d4 < 0.0;
        // Entity.java:690 updateFallState tracks fall distance only, which nothing in 1.1 reads.
        if (d4 != dy) {
            s.vy = 0.0;                                  // Entity.java:697-699 -> Block.onLanded, Block.java:1125-1127
        }
        // Entity.java:691-696 motionX/Z zeroing: d3 == dx and d5 == dz here, nothing to zero.
    }
}
