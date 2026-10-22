package dev.kairos.core.sim;

import dev.kairos.core.trace.TraceInput;

/**
 * Phase 1 simulator (tasks 1.1-1.2): the local player's movement tick - walk, sprint,
 * jump, air acceleration, drag, the 0.005 momentum threshold, and the real block
 * collision sweep against the shapes dumped from the game registry.
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
 * <p><b>Scope.</b> W/S/A/D movement at any yaw with jump, sneak and sprint, on
 * the scenario's collision world from {@code data/1.8.9/blocks.json} (task 1.2;
 * {@link CollisionWorld} owns the queries, this class owns the rules), with the
 * per-block {@code Block.slipperiness} under the feet read from the same dump (task 1.3;
 * ice/packed ice 0.98f, stone/air 0.6f), the ladder/vine clamp + climb impulse
 * (task 1.4; EntityLivingBase.java:1265-1282) and the game's sine/cosine lookup table
 * for non-axis yaw and strafe (task 1.6; Entity.java:976-988,
 * EntityLivingBase.java:1222-1234). Still out of scope: water/lava/flying (after
 * Gate 2), pushOutOfBlocks and the item-use axis scaling (not recorded, see notes
 * section 4). Tier C blocks (slime/soul sand/cobweb) are not
 * modelled: slime's 0.8 is read from the dump, but its bounce and entity effects are
 * out of scope until after Gate 2. No jump classifier anywhere: step-up, head bumps,
 * the sneaking edge clamp and fence/wall/pane shapes fall out of the game's own sweep
 * and block predicates (AGENTS.md).
 *
 * <p><b>Purity and allocation.</b> {@link #step(State, TraceInput, CollisionWorld)} reads
 * and writes only the caller-owned {@link State} plus the world's query scratch: same
 * state + same input gives the same result, always (AGENTS.md rule 2), and there is no
 * allocation on the tick path (rule 7). The public contract is the mutable-state form;
 * solvers copy states when they need branching.
 */
public final class MovementSim {

    /** Entity width 0.6f, halved as a float (Entity.setPosition, Entity.java:392-399). */
    private static final float WIDTH_HALF = 0.6f / 2.0f;

    /** Entity height 1.8f (Entity.java:172); EntityPlayer keeps it. */
    private static final float HEIGHT = 1.8f;

    /**
     * EntityLivingBase.stepHeight = 0.6f (EntityLivingBase.java:221, constructor). The
     * local player never changes it; only EntityPlayerMP (:197) and remote players
     * (EntityOtherPlayerMP.java:31) zero it.
     */
    private static final float STEP_HEIGHT = 0.6f;

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
         * (Entity.java:604-608, 756-760). Y needs no min extents: end-of-tick posY IS
         * the box's minY (:758).
         */
        public double minX;
        public double maxX;
        public double minZ;
        public double maxZ;

        /**
         * The entity box's maxY. Carried across ticks, NOT re-derived from y: the game
         * builds it once per teleport as {@code y + (double)height}
         * (Entity.setPosition, Entity.java:396-398) and then only ever offsets the box
         * (:599, :604, :608, :623, :639, :666), so rounding drift accumulates.
         */
        public double maxY;

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

        /**
         * The previous tick's {@code movementInput.sneak} key ({@code flag1},
         * EntityPlayerSP.java:592, read before :595 updates the input and used by the
         * sprint-start test at :606).
         */
        public boolean prevSneak;

        /** EntityLivingBase.jumpMovementFactor, the air acceleration set by the previous tick (notes 1.6). */
        public float jumpMovementFactor;
    }

    private MovementSim() {
    }

    /**
     * State matching a recorded {@code tick:-1} anchor line: the settle left the player at
     * rest on the floor with no keys, so the timers are idle, {@code moveForward} is 0 and
     * the last tail already ran with the walk air factor. The box is built from the
     * position exactly like the teleport did (Entity.setPosition, Entity.java:392-399),
     * and the settle's clamped-to-zero offsets left it untouched.
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
        s.prevSneak = false;                             // the settle ran with zero keys
        s.jumpMovementFactor = SPEED_IN_AIR;             // tail of the last settle tick
        s.minX = x - (double) WIDTH_HALF;
        s.maxX = x + (double) WIDTH_HALF;
        s.minZ = z - (double) WIDTH_HALF;
        s.maxZ = z + (double) WIDTH_HALF;
        s.maxY = y + (double) HEIGHT;                    // Entity.java:396-398
        return s;
    }

    /**
     * Advances one game tick, in place, against the scenario's collision world.
     *
     * <p>Any yaw and any movement axes are accepted now (task 1.6); the remaining
     * out-of-scope behaviour is listed in the class comment.
     */
    public static void step(State s, TraceInput in, CollisionWorld world) {
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
        if (in.jump != 0) {                              // :1513-1525; no water/lava in scope
            if (s.onGround && s.jumpTicks == 0) {
                jump(s, in.yaw);
                s.jumpTicks = 10;                        // :1519-1521
            }
        } else {
            s.jumpTicks = 0;                             // :1523-1524
        }
        float strafe = in.ms * 0.98f;                    // :1528, left positive
        float forward = s.moveForward * 0.98f;           // :1529
        moveEntityWithHeading(s, strafe, forward, in.sneak != 0, world, in.yaw);  // :1531

        // --- EntityPlayer.onLivingUpdate tail
        //     (notes section 1.6, EntityPlayer.java:534-538): next tick's air acceleration.
        s.jumpMovementFactor = SPEED_IN_AIR;
        if (s.sprinting) {
            s.jumpMovementFactor = (float) ((double) s.jumpMovementFactor + (double) SPEED_IN_AIR * 0.3);
        }
        // :538 setAIMoveSpeed is a no-op for players: getAIMoveSpeed() reads the attribute
        // live, which aimoveSpeed() below mirrors.

        // The moveEntity sneak clamp reads isSneaking() = movementInput.sneak
        // (EntityPlayerSP.java:527-530, no inventory open), and the next tick's sprint
        // test reads this tick's key as its flag1 (:592).
        s.prevSneak = in.sneak != 0;
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
        boolean wasSneak = s.prevSneak;                  // :592 flag1, BEFORE the keys are read
        boolean wasForward = s.moveForward >= 0.8f;      // :594, BEFORE the keys are read
        s.moveForward = in.mf;                           // :595 (recorded axis, notes 1.4)
        // :596-599 item-use axis scaling and :601-604 pushOutOfBlocks are not modelled:
        // neither is recorded in the traces (notes section 4 lists pushOutOfBlocks).
        boolean foodOk = true;                           // :605 food > 6 || allowFlying (constant in the harness)
        if (s.onGround && !wasSneak && !wasForward && s.moveForward >= 0.8f && !s.sprinting && foodOk) {
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
    private static void jump(State s, float yaw) {
        s.vy = (double) 0.42f;                           // getJumpUpwardsMotion :1215-1217, :1223
        if (s.sprinting) {
            // Sprint boost :1227-1231 along the facing heading (-sin, +cos). The angle
            // uses the parenthesized radians expression, which rounds differently from
            // moveFlying's shape (Entity.java:983-984): do not share a helper between them.
            float f = yaw * ((float) Math.PI / 180);
            s.vx -= (double) (MathHelper.sin(f) * 0.2f);
            s.vz += (double) (MathHelper.cos(f) * 0.2f);
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
     * (EntityLivingBase.java:1250-1286; notes sections 1.7, 2.1-2.6 and 2.9). The
     * ladder/vine clamp and climb impulse are :1265-1278 and :1280-1282; the
     * water/lava branches (:1287-1323) are not modelled.
     */
    private static void moveEntityWithHeading(State s, float strafe, float forward,
            boolean sneaking, CollisionWorld world, float yaw) {
        // :1254-1257: on the ground, f4 = block.slipperiness * 0.91f for the block under
        // the feet (floor(posX), floor(box.minY) - 1, floor(posZ)); air otherwise.
        float f4 = 0.91f;
        if (s.onGround) {
            f4 = world.slipperinessAt(s.x, s.y, s.z) * 0.91f;
        }
        float f = 0.16277136f / (f4 * f4 * f4);          // :1258
        float f5 = s.onGround ? aimoveSpeed(s) * f : s.jumpMovementFactor;  // :1259
        moveFlying(s, strafe, forward, f5, yaw);         // :1260, notes section 2.3
        // :1261-1264 recompute f4 with the SAME pre-move inputs (moveEntity has not run
        // yet), so the second lookup always agrees with the first.
        f4 = 0.91f;
        if (s.onGround) {
            f4 = world.slipperinessAt(s.x, s.y, s.z) * 0.91f;
        }
        // :1265-1278: on a ladder/vine, clamp horizontal motion to +/-0.15f (float
        // widened, MathHelper.clamp_double :132-138), fall distance is reset (not
        // recorded), downward motion is floored at -0.15 and a sneaking player does not
        // slide down. isOnLadder() reads the PRE-move feet cell.
        if (isOnLadder(s, world)) {
            float f6 = 0.15f;
            s.vx = clamp(s.vx, -(double) f6, (double) f6);
            s.vz = clamp(s.vz, -(double) f6, (double) f6);
            if (s.vy < -0.15) {
                s.vy = -0.15;
            }
            if (sneaking && s.vy < 0.0) {
                s.vy = 0.0;
            }
        }
        moveEntity(s, sneaking, world);                  // :1279, notes section 2.8
        // :1280-1282: pushing horizontally into the ladder/vine climbs at 0.2 per tick.
        // The game asks isOnLadder() again here, at the POST-move position.
        if (s.colH && isOnLadder(s, world)) {
            s.vy = 0.2;
        }
        s.vy -= 0.08;                                    // :1283 gravity
        s.vy *= (double) 0.98f;                          // :1284, the widened float 0.9800000190734863
        s.vx *= (double) f4;                             // :1285
        s.vz *= (double) f4;                             // :1286
    }

    /** EntityLivingBase.isOnLadder (EntityLivingBase.java:878-883), feet cell. */
    private static boolean isOnLadder(State s, CollisionWorld world) {
        return world.climbableAt(s.x, s.y, s.z);
    }

    /** MathHelper.clamp_double (MathHelper.java:132-138). */
    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * Entity.moveFlying (Entity.java:976-988; notes section 2.3). The angle keeps the
     * game's exact multiply-then-divide shape, which rounds differently from the sprint
     * jump boost's parenthesized expression (EntityLivingBase.java:1229).
     */
    private static void moveFlying(State s, float strafe, float forward, float friction,
            float yaw) {
        float len2 = strafe * strafe + forward * forward;
        if (len2 >= 1.0E-4f) {
            float len = (float) Math.sqrt(len2);         // MathHelper.sqrt_float, MathHelper.java:40-42
            if (len < 1.0f) {
                len = 1.0f;
            }
            float mul = friction / len;
            strafe *= mul;
            forward *= mul;
            float sin = MathHelper.sin(yaw * (float) Math.PI / 180.0f);
            float cos = MathHelper.cos(yaw * (float) Math.PI / 180.0f);
            s.vx += (double) (strafe * cos - forward * sin);
            s.vz += (double) (forward * cos + strafe * sin);
        }
    }

    /**
     * Entity.moveEntity (Entity.java:553-712; notes section 2.8). The noClip, isInWeb and
     * ridingEntity branches are never taken in the harness, and the walk-distance
     * bookkeeping (:700-712) does not feed back into motion.
     *
     * <p>The sweep is the game's, line by line: sneak edge clamp first (:576-593), then
     * the axis passes Y -> X -> Z over one query list (:594-608), then the step-up retry
     * (:609-673) with both candidate boxes and the "did the retry actually help" revert.
     * All queries and box comparisons happen in {@link CollisionWorld} on the dumped
     * shapes; this method only sequences them.
     */
    private static void moveEntity(State s, boolean sneaking, CollisionWorld world) {
        double dx = s.vx;
        double dy = s.vy;
        double dz = s.vz;
        double d3 = dx;                                  // :573-575 the raw motion
        double d4 = dy;
        double d5 = dz;

        double minX = s.minX;
        double minY = s.y;                               // posY IS box.minY (:756-760)
        double minZ = s.minZ;
        double maxX = s.maxX;
        double maxY = s.maxY;                            // carried, see State.maxY
        double maxZ = s.maxZ;

        boolean flag = s.onGround && sneaking;           // :576; && instanceof EntityPlayer is always true
        if (flag) {                                      // :577-593 sneak edge clamp, 0.05 steps
            double d6 = 0.05;                            // :578
            while (dx != 0.0 && world.isEmpty(minX + dx, minY - 1.0, minZ,
                    maxX + dx, maxY - 1.0, maxZ)) {      // :579 box.offset(x, -1.0, 0.0)
                dx = dx < d6 && dx >= -d6 ? 0.0 : (dx > 0.0 ? (dx -= d6) : (dx += d6));
                d3 = dx;                                 // :580-581
            }
            while (dz != 0.0 && world.isEmpty(minX, minY - 1.0, minZ + dz,
                    maxX, maxY - 1.0, maxZ + dz)) {      // :583 box.offset(0.0, -1.0, z)
                dz = dz < d6 && dz >= -d6 ? 0.0 : (dz > 0.0 ? (dz -= d6) : (dz += d6));
                d5 = dz;                                 // :584-585
            }
            while (dx != 0.0 && dz != 0.0 && world.isEmpty(minX + dx, minY - 1.0, minZ + dz,
                    maxX + dx, maxY - 1.0, maxZ + dz)) { // :587 box.offset(x, -1.0, z)
                dx = dx < d6 && dx >= -d6 ? 0.0 : (dx > 0.0 ? (dx -= d6) : (dx += d6));
                d3 = dx;
                dz = dz < d6 && dz >= -d6 ? 0.0 : (dz > 0.0 ? (dz -= d6) : (dz += d6));
                d5 = dz;                                 // :588-591
            }
        }
        double preMinX = minX;                           // :595 axisalignedbb = the pre-move box
        double preMinY = minY;
        double preMinZ = minZ;
        double preMaxX = maxX;
        double preMaxY = maxY;
        double preMaxZ = maxZ;

        // :594 list1 = world.getCollidingBoundingBoxes(this, box.addCoord(x, y, z))
        world.query(addMin(minX, dx), addMin(minY, dy), addMin(minZ, dz),
                addMax(maxX, dx), addMax(maxY, dy), addMax(maxZ, dz));
        for (int i = 0; i < world.queryCount(); i++) {   // :596-598 Y first
            dy = world.yOffset(world.queryBox(i), minX, minY, minZ, maxX, maxY, maxZ, dy);
        }
        minY += dy;                                      // :599 offset(0.0, y, 0.0)
        maxY += dy;
        boolean flag1 = s.onGround || d4 != dy && d4 < 0.0;  // :600
        for (int i = 0; i < world.queryCount(); i++) {   // :601-603 then X
            dx = world.xOffset(world.queryBox(i), minX, minY, minZ, maxX, maxY, maxZ, dx);
        }
        minX += dx;                                      // :604 offset(x, 0.0, 0.0)
        maxX += dx;
        for (int i = 0; i < world.queryCount(); i++) {   // :605-608 then Z
            dz = world.zOffset(world.queryBox(i), minX, minY, minZ, maxX, maxY, maxZ, dz);
        }
        minZ += dz;                                      // :608 offset(0.0, 0.0, z)
        maxZ += dz;

        if ((double) STEP_HEIGHT > 0.0 && flag1 && (d3 != dx || d5 != dz)) {  // :609-673
            double d = dx;                               // :610-612
            double d7 = dy;
            double d8 = dz;
            double nMinX = minX;                         // :613 axisalignedbb3, the post-pass box
            double nMinY = minY;
            double nMinZ = minZ;
            double nMaxX = maxX;
            double nMaxY = maxY;
            double nMaxZ = maxZ;
            minX = preMinX;                              // :614 back to the pre-move box
            minY = preMinY;
            minZ = preMinZ;
            maxX = preMaxX;
            maxY = preMaxY;
            maxZ = preMaxZ;
            dy = (double) STEP_HEIGHT;                   // :615 y = stepHeight (float widened)
            // :616 list = world.getCollidingBoundingBoxes(this, box.addCoord(d3, y, d5))
            world.query(addMin(minX, d3), addMin(minY, dy), addMin(minZ, d5),
                    addMax(maxX, d3), addMax(maxY, dy), addMax(maxZ, d5));

            // Candidate A (:617-633): lift the box extended by the move, then move it.
            double a5MinX = addMin(minX, d3);            // :618 axisalignedbb5 = box.addCoord(d3, 0.0, d5)
            double a5MinY = minY;
            double a5MinZ = addMin(minZ, d5);
            double a5MaxX = addMax(maxX, d3);
            double a5MaxY = maxY;
            double a5MaxZ = addMax(maxZ, d5);
            double d9 = dy;                              // :619-622
            for (int i = 0; i < world.queryCount(); i++) {
                d9 = world.yOffset(world.queryBox(i), a5MinX, a5MinY, a5MinZ,
                        a5MaxX, a5MaxY, a5MaxZ, d9);
            }
            double aMinX = preMinX;                      // :623 offset(0.0, d9, 0.0)
            double aMinY = preMinY + d9;
            double aMinZ = preMinZ;
            double aMaxX = preMaxX;
            double aMaxY = preMaxY + d9;
            double aMaxZ = preMaxZ;
            double d15 = d3;                             // :624-627
            for (int i = 0; i < world.queryCount(); i++) {
                d15 = world.xOffset(world.queryBox(i), aMinX, aMinY, aMinZ,
                        aMaxX, aMaxY, aMaxZ, d15);
            }
            aMinX += d15;                                // :628
            aMaxX += d15;
            double d16 = d5;                             // :629-632
            for (int i = 0; i < world.queryCount(); i++) {
                d16 = world.zOffset(world.queryBox(i), aMinX, aMinY, aMinZ,
                        aMaxX, aMaxY, aMaxZ, d16);
            }
            aMinZ += d16;                                // :633
            aMaxZ += d16;

            // Candidate B (:634-649): move first, then lift the plain box.
            double d17 = dy;                             // :635-638
            for (int i = 0; i < world.queryCount(); i++) {
                d17 = world.yOffset(world.queryBox(i), preMinX, preMinY, preMinZ,
                        preMaxX, preMaxY, preMaxZ, d17);
            }
            double bMinX = preMinX;                      // :639 offset(0.0, d17, 0.0)
            double bMinY = preMinY + d17;
            double bMinZ = preMinZ;
            double bMaxX = preMaxX;
            double bMaxY = preMaxY + d17;
            double bMaxZ = preMaxZ;
            double d18 = d3;                             // :640-643
            for (int i = 0; i < world.queryCount(); i++) {
                d18 = world.xOffset(world.queryBox(i), bMinX, bMinY, bMinZ,
                        bMaxX, bMaxY, bMaxZ, d18);
            }
            bMinX += d18;                                // :644
            bMaxX += d18;
            double d19 = d5;                             // :645-648
            for (int i = 0; i < world.queryCount(); i++) {
                d19 = world.zOffset(world.queryBox(i), bMinX, bMinY, bMinZ,
                        bMaxX, bMaxY, bMaxZ, d19);
            }
            bMinZ += d19;                                // :649
            bMaxZ += d19;

            double d20 = d15 * d15 + d16 * d16;          // :650 the farther candidate wins
            double d10 = d18 * d18 + d19 * d19;          // :651
            if (d20 > d10) {                             // :652-656
                dx = d15;
                dz = d16;
                dy = -d9;
                minX = aMinX;
                minY = aMinY;
                minZ = aMinZ;
                maxX = aMaxX;
                maxY = aMaxY;
                maxZ = aMaxZ;
            } else {                                     // :657-661
                dx = d18;
                dz = d19;
                dy = -d17;
                minX = bMinX;
                minY = bMinY;
                minZ = bMinZ;
                maxX = bMaxX;
                maxY = bMaxY;
                maxZ = bMaxZ;
            }
            for (int i = 0; i < world.queryCount(); i++) {  // :663-665 settle down onto the step
                dy = world.yOffset(world.queryBox(i), minX, minY, minZ, maxX, maxY, maxZ, dy);
            }
            minY += dy;                                  // :666
            maxY += dy;
            if (d * d + d8 * d8 >= dx * dx + dz * dz) {  // :667-672 the retry gained nothing
                dx = d;
                dy = d7;
                dz = d8;
                minX = nMinX;
                minY = nMinY;
                minZ = nMinZ;
                maxX = nMaxX;
                maxY = nMaxY;
                maxZ = nMaxZ;
            }
        }

        s.minX = minX;                                   // :676 resetPositionToBB (:756-760)
        s.maxX = maxX;
        s.minZ = minZ;
        s.maxZ = maxZ;
        s.maxY = maxY;
        s.x = (minX + maxX) / 2.0;                       // :757
        s.y = minY;                                      // :758
        s.z = (minZ + maxZ) / 2.0;                       // :759
        s.colH = d3 != dx || d5 != dz;                   // :677
        s.colV = d4 != dy;                               // :678
        s.onGround = s.colV && d4 < 0.0;                 // :679
        if (d3 != dx) {                                  // :691-693
            s.vx = 0.0;
        }
        if (d5 != dz) {                                  // :694-696
            s.vz = 0.0;
        }
        if (d4 != dy) {
            // :697-699 -> Block.onLanded, which zeroes motionY for every block in scope
            // (Block.java:1125-1127; slime alone overrides it, BlockSlime.java:41).
            s.vy = 0.0;
        }
        // :700-712 updateFallState/canTriggerWalking: walk distance and fall distance are
        // tracked but not recorded, and nothing in scope reads them back.
    }

    /** AxisAlignedBB.addCoord's min side (AxisAlignedBB.java:42-65). */
    private static double addMin(double min, double delta) {
        return delta < 0.0 ? min + delta : min;
    }

    /** AxisAlignedBB.addCoord's max side (AxisAlignedBB.java:42-65). */
    private static double addMax(double max, double delta) {
        return delta > 0.0 ? max + delta : max;
    }
}
