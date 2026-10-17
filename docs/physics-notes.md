# Physics notes (Minecraft 1.8.9, the local player)

These notes describe exactly what the game does to the player each tick, so the simulator
in `core` can copy it. They are written to be **mechanical**: every rule cites the
`mc-src/` file and line it came from, or a specific MCPK wiki page. If a rule has no
citation it is marked **UNVERIFIED**, which means "do not build on this yet".

The source is the decompiled MCP `stable_22` tree in `mc-src/` (gitignored, never
committed). Line numbers refer to that tree. They are **not** upstream line numbers.

Read the worked example at the end. It reproduces six ticks of the real trace
`loader-1.8.9/run/kairos/traces/sprintjump_flat_01_run01.jsonl` by hand from the formulas
above it, with the game's exact `float`/`double` mix. If a future edit breaks a number
there, the notes are wrong.

Owner still has to read and understand this before Phase 1; a second model reviews it.

---

## 1. The shape of one tick

A "tick" is one call to `Minecraft.runTick()`. The recorder writes a line on the
`ClientTickEvent.END` event (`KairosRecorder`, `loader-1.8.9/src/main/java/dev/kairos/loader/KairosRecorder.java`),
so a recorded line is the player state **after** all of that tick's physics.

### 1.1 Frame vs tick

1. `Minecraft.runGameLoop` counts how many ticks the timer owes this frame and runs
   `runTick()` that many times, in a loop
   (`net/minecraft/client/Minecraft.java:993-994`).
2. Only **after** that loop does the frame do mouse look, in
   `EntityRenderer.updateCameraAndRender` (`Minecraft.java:1015`,
   `net/minecraft/client/renderer/EntityRenderer.java:849`), which calls
   `mouseXYChange` (`EntityRenderer.java:865`), scales the raw deltas by
   `(sensitivity*0.6+0.2)^3*8` (`:866-869`), and then calls
   `thePlayer.setAngles(...)` (`EntityRenderer.java:881,885`).

Consequence: **yaw/pitch change at most once per frame, between tick batches.** Inside
one tick the yaw is constant, and the yaw read at end of tick is the yaw the physics used.
`setAngles` adds `deltaYaw * 0.15`, subtracts `deltaPitch * 0.15`, clamps pitch to
±90 and updates the `prev*` values (`net/minecraft/entity/Entity.java:405-413`).
(The `+0.15` factors are applied in `double`, then cast back to `float`.)

### 1.2 Order inside `runTick()`

3. `onPreClientTick()` fires the Forge `ClientTickEvent.START`
   (`Minecraft.java:1456`; posts `Phase.START` in
   `net/minecraftforge/fml/common/FMLCommonHandler.java:329-332`).
4. GUI, input and `playerController.updateController()` run
   (`Minecraft.java:1457-1700`). This is where key state is read.
5. **`theWorld.updateEntities()`** (`Minecraft.java:1739`, only when not paused) —
   all entity physics.
6. `theWorld.tick()` (`Minecraft.java:1752`) — world time, spawn queue, block updates
   (`net/minecraft/client/multiplayer/WorldClient.java:83-101`). It does **not** move the
   player.
7. `onPostClientTick()` fires `ClientTickEvent.END` (`Minecraft.java:1777`;
   posts `Phase.END` in `FMLCommonHandler.java:334-337`). The recorder writes here.

### 1.3 `updateEntities` → the player's `onUpdate`

`World.updateEntities` (`net/minecraft/world/World.java:1307`) loops the loaded entities
and calls `updateEntity` (`World.java:1463`) → `updateEntityWithOptionalForce`
(`World.java:1470`). For an updatable entity, in order:

8. Snapshot `lastTickPosX/Y/Z` and `prevRotationYaw/Pitch` (`World.java:1481-1485`).
9. `++ticksExisted` (`World.java:1487`). This is the `te` field in the trace.
10. `entityIn.onUpdate()` (`World.java:1491`).

The player's `onUpdate` chain:

11. `EntityPlayerSP.onUpdate` (`net/minecraft/client/entity/EntityPlayerSP.java:152`;
    the whole chain is skipped if the player's chunk is not loaded, `:153`)
    → `EntityPlayer.onUpdate` (`net/minecraft/entity/player/EntityPlayer.java:277`)
    → `EntityLivingBase.onUpdate` (`net/minecraft/entity/EntityLivingBase.java:1363`)
    → `Entity.onUpdate`/`onEntityUpdate` (`Entity.java:418`).
12. Then `EntityLivingBase.onUpdate` calls `this.onLivingUpdate()`
    (`EntityLivingBase.java:1396`). The chain is
    `EntityPlayerSP.onLivingUpdate` (`EntityPlayerSP.java:551`)
    → `super.onLivingUpdate()` (`EntityPlayerSP.java:663`)
    → `EntityPlayer.onLivingUpdate` (`EntityPlayer.java:515`)
    → `super.onLivingUpdate()` (`EntityPlayer.java:529`)
    → `EntityLivingBase.onLivingUpdate` (`EntityLivingBase.java:1473`).

The client player really does run physics, because `EntityPlayerSP.isServerWorld()`
returns `true` (`EntityPlayerSP.java:443-446`). This matters: the `!isServerWorld()`
branches (the `*0.98` drag, `EntityLivingBase.java:1487-1491`) are for **remote** entities
and never run on the local player.

### 1.4 `EntityPlayerSP.onLivingUpdate` (input → sprint)

Before the physics (all in `EntityPlayerSP.java`):

13. `sprintingTicksLeft` / `sprintToggleTimer` countdown (`:553-561`).
14. Remember `flag2 = movementInput.moveForward >= 0.8` **before** reading keys (`:594`).
15. `movementInput.updatePlayerMoveState()` (`:595`).
16. If using an item and not riding, scale both axes by `0.2` and clear the sprint-toggle
    timer (`:596-599`).
17. Call `pushOutOfBlocks` at the player's four horizontal bounding-box sample points
    (`:601-604`). This can move the player without input and occurs before sprint decisions.
18. Sprint start (`:606-616`):
    - `:606-611`: if onGround, not sneaking, the previous tick's `moveForward < 0.8`
      (`!flag2`), current `moveForward >= 0.8`, not already sprinting, food > 6
      (or flying allowed), not using an item, not blind: if the sprint **key** is down,
      `setSprinting(true)`; if not, start the 7-tick double-tap timer.
    - `:613-614`: independently, if not sprinting, `moveForward >= 0.8`, food ok, not
      using an item, not blind and the sprint key is down: `setSprinting(true)`.
19. Sprint stop: if sprinting and (`moveForward < 0.8` or collided horizontally or food
    low) → `setSprinting(false)` (`:616-618`; `isCollidedHorizontally` here is the value
    from the **previous** tick's `moveEntity`).
20. Then `super.onLivingUpdate()` (`:663`); if the player lands while flying, flight is
    turned off afterwards (`:664-667`).

`setSprinting` toggles the sprint flag and adds/removes the "Sprinting speed boost"
attribute modifier, amount `0.3f`, operation `2` (`EntityLivingBase.java:1143-1152`;
modifier defined at `EntityLivingBase.java:69`). Operation `2` means "multiply the total
by `1 + amount`" (`net/minecraft/entity/ai/attributes/ModifiableAttributeInstance.java:150-163`),
so sprinting multiplies walk speed by **1.3** — and it takes effect **within the same
tick**, because the physics reads the attribute live (see 2.2).

**How inputs arrive.** `MovementInputFromOptions.updatePlayerMoveState`
(`net/minecraft/util/MovementInputFromOptions.java:21-42`) turns key state into the axes:
forward `1.0`, back `-1.0`, left strafe `+1.0`, right strafe `-1.0`; jump/sneak are
booleans; sneaking multiplies both axes by `0.3` (`:38-41`, computed in `double`). The
key state itself comes from `KeyBinding.isKeyDown()` reading `GameSettings.keyBind*`.
Note for tracing: `Minecraft.runTick` maps physical key events onto the same bindings in
its keyboard loop (`Minecraft.java:1561-1563`) *after* the `ClientTickEvent.START`
handlers, so a physical key press can clobber a key state the harness set — this bit
task 0.5 (see `docs/JOURNAL.md`; the scenario runner swaps in a `KeyBinding` that ignores
`setKeyDown`). The trace's `mf`/`ms` fields record the resulting axis values.

### 1.5 `EntityLivingBase.onLivingUpdate` (the core)

21. Decrement `jumpTicks` if > 0 (`:1474-1476`).
22. If `newPosRotationIncrements > 0`, interpolate toward `newPosX/Y/Z` and shrink the
    counter (`:1477-1486`); else if not a server world, drag `motionX/Y/Z` by `0.98`
    (`:1487-1491`). The local player takes **neither** (see 1.3).
23. **Momentum threshold**: if `abs(motionX) < 0.005` set `motionX = 0`, and the same for
    `motionY` and `motionZ` (`:1492-1500`).
24. If `isMovementBlocked()` zero the jump/strafe/forward inputs and
    `randomYawVelocity` (`:1502-1506`), else if `isServerWorld()` call
    `updateEntityActionState()` (`:1507-1510`). For the local
    player, `EntityPlayerSP.updateEntityActionState()` copies input into the entity only
    when `isCurrentViewEntity()` is true (`EntityPlayerSP.java:532-538`). It copies
    `movementInput.moveStrafe` to `moveStrafing`, `movementInput.moveForward` to
    `moveForward`, and `movementInput.jump` to `isJumping`. A normal local player is the
    current view entity.
25. Jump block (`:1513-1525`): if `isJumping` and in water → `updateAITick()`
    (`motionY += 0.04`), else if in lava → `handleJumpLava()` (`motionY += 0.04`), else if
    `onGround && jumpTicks == 0` → `jump(); jumpTicks = 10`. If not jumping,
    `jumpTicks = 0`.
26. Scale inputs: `moveStrafing *= 0.98f`, `moveForward *= 0.98f`,
    `randomYawVelocity *= 0.9f` (`:1528-1530`).
27. `moveEntityWithHeading(moveStrafing, moveForward)` (`:1531`).
28. After travel, `collideWithNearbyEntities()` runs **only when `!worldObj.isRemote`**
    (`:1533-1537`) — i.e. on the server, not on the client player.

### 1.6 `EntityPlayer.onLivingUpdate` tail

29. After the physics, set `jumpMovementFactor = speedInAir`, add
    `speedInAir * 0.3` (computed in `double`) if sprinting, and `setAIMoveSpeed(attribute)`
    (`EntityPlayer.java:534-538`). **These are used on the NEXT tick**, not the current
    one. (`iattributeinstance.setBaseValue(...)` at `:531-533` is server-only.)

### 1.7 `moveEntityWithHeading` (normal ground/air path)

All in `EntityLivingBase.java` unless noted:

30. `f4 = 0.91f`; if `onGround`, `f4 = block.slipperiness * 0.91f`, where the block is at
    `floor(posX)`, `floor(minY) - 1`, `floor(posZ)` (`:1254-1257`).
31. Acceleration scale `f = 0.16277136f / (f4 * f4 * f4)` (`:1258`, computed in `float`).
32. `f5 = onGround ? getAIMoveSpeed() * f : jumpMovementFactor` (`:1259`).
33. `moveFlying(strafe, forward, f5)` (`:1260`) — adds horizontal motion (section 2.3).
34. Recompute `f4` exactly as in step 30 (`:1261-1264`). This value is the one used for
    friction at step 38, and it still reflects `onGround` from **before** `moveEntity`.
35. Ladder clamp: if on a ladder, clamp `motionX/Z` to ±0.15, zero `fallDistance`,
    clamp `motionY >= -0.15`, and if sneaking `motionY >= 0` (`:1265-1277`).
36. `moveEntity(motionX, motionY, motionZ)` (`:1279`) — collision, section 2.8. If
    horizontal collision occurred while on a ladder, set `motionY = 0.2` (`:1280-1282`).
37. Gravity: `motionY -= 0.08`, except on the client when the player's chunk is not
    loaded, where `motionY` is set to `-0.1` above y=0 or `0.0` at/below (`:1283`).
    The local-player expression collapses to plain `-= 0.08` in normal play.
38. `motionY *= (double)0.98f` (`:1284`), then `motionX *= (double)f4`,
    `motionZ *= (double)f4` (`:1285-1286`). Note the widened `float` constant:
    `(double)0.98f = 0.9800000190734863`.

`EntityPlayer.moveEntityWithHeading` (`EntityPlayer.java:1419-1435`) wraps this: if flying
(creative), it temporarily swaps in `flySpeed * (sprinting ? 2 : 1)` as
`jumpMovementFactor`, calls super, then sets `motionY = motionY * 0.6`. Not used while
walking.

---

## 2. The formulas

Types, once: `posX/Y/Z` and `motionX/Y/Z` are **double**. `f4`, `f`, `f5`,
`moveStrafing`, `moveForward`, `jumpMovementFactor`, `aimoveSpeed` are **float**. Several
constants are `float` literals widened when used in `double` context; the exact widened
values matter for reproducing traces digit-for-digit:
`(double)0.42f = 0.41999998688697815`, `(double)0.98f = 0.9800000190734863`,
`(double)0.2f = 0.20000000298023224`, `(double)0.1f = 0.10000000149011612`,
`(double)0.3f = 0.30000001192092896`.

### 2.1 Friction (`f4`)

- Air: `0.91f` (`EntityLivingBase.java:1254,1261`).
- Ground: `slipperiness * 0.91f` (`EntityLivingBase.java:1256,1263`). For stone
  (`slipperiness = 0.6f`) the `float` product is `0.54600006f`
  (= `0.546000063419342` as a double — **not** exactly `0.546`).
- The per-block `slipperiness` values (0.6 normal, 0.98 ice, 0.8 slime — MCPK wiki,
  "Slipperiness" page) must come from the generated block data, not from memory; that
  data does not exist in this repo yet (section 4).

### 2.2 Acceleration

- `f = 0.16277136f / f4^3` (`EntityLivingBase.java:1258`); `0.16277136f` is the
  hand-rounded `0.546^3`, so for a block of slipperiness `S` this is
  `f ≈ (0.6 / S)^3`. For normal ground it is `0.9999998f`.
- Ground: `f5 = getAIMoveSpeed() * f` (`:1259`). `getAIMoveSpeed()` for a player is the
  live `movementSpeed` attribute (`EntityPlayer.java:1437-1440`), base `0.1f`
  (`EntityPlayer.java:214`), multiplied by `1 + 0.30000001192092896` while sprinting
  (section 1.4, attribute op 2). Sprinting: `0.13000001f`; walking: `0.10000000149011612f`.
- Air: `f5 = jumpMovementFactor` (`:1259`). Field default `0.02f`
  (`EntityLivingBase.java:125`). For a player it is set at the end of each tick to
  `speedInAir` (`0.02f`), plus `speedInAir * 0.3` if sprinting → **`0.025999999f`
  (`0.025999998673796654`) while sprinting** (`EntityPlayer.java:183-184,534-536`).
- This matches the MCPK wiki "Horizontal Movement Formulas" acceleration
  `0.1 * M * E * (0.6/S)^3`, with `M = 1.3` sprinting and air acceleration `0.02 * M`
  (see 2.10).

### 2.3 `moveFlying` (`Entity.java:976-988`)

Given `strafe`, `forward`, `friction` (all `float`):

1. `len2 = strafe*strafe + forward*forward`. If `len2 < 1.0e-4f`, do nothing.
2. `len = sqrt_float(len2)`; if `len < 1.0`, set `len = 1.0`.
3. `mul = friction / len`.
4. `s = strafe*mul`, `fw = forward*mul`.
5. `sin = MathHelper.sin(yaw*(float)Math.PI/180.0f)`, `cos = MathHelper.cos(...)`
   (`Entity.java:983-984`).
6. `motionX += (double)(s*cos - fw*sin)`
7. `motionZ += (double)(fw*cos + s*sin)`

Key detail for replay: because the axis is multiplied by `0.98f` first (step 26), a single
held forward key gives `forward = 0.98f`, whose length is `0.98 < 1`, so `len` is clamped to
`1`. The added speed is therefore `0.98f * f5` in the `(-sin, +cos)` direction — but the
product is computed in `float` and only then widened to `double` when added.

`MathHelper.sin/cos` are not the JDK's: they read a 65536-entry table
(`net/minecraft/util/MathHelper.java:17,29-38,496-498`). `sqrt_float` is a plain float
square root (`MathHelper.java:40-42`). For yaw 0 exactly, the table gives
`sin = 0.0f` and `cos = 1.0f`.

### 2.4 Yaw direction

Using `sin = sin(yaw)`, `cos = cos(yaw)`:

- `motionX` gets `-fw*sin`, `motionZ` gets `+fw*cos`.
- So the forward direction is `(-sin(yaw), +cos(yaw))`.
- Yaw is in degrees, converted with `yaw * (float)Math.PI / 180.0f` (`Entity.java:983-984`).

### 2.5 Jump

`EntityLivingBase.jump()` (`:1222-1234`):

1. `motionY = getJumpUpwardsMotion()` = `0.42f` → `0.41999998688697815`
   (`:1215-1216,1223`).
2. Potion of jump adds `(amplifier + 1) * 0.1` (`:1224-1226`).
3. **If sprinting**, add the sprint-jump boost:
   `motionX -= (double)(sin(yaw*PI/180) * 0.2f)` and
   `motionZ += (double)(cos(yaw*PI/180) * 0.2f)` (`:1227-1231`). This is the same
   `(-sin,+cos)` direction, magnitude `0.2f`.
4. `isAirBorne = true`, then `ForgeHooks.onLivingJump` (`:1232-1233`).

`jumpTicks` is set to `10` by the caller (`:1519-1521`); while it is > 0 the same jump
held cannot fire again. It decrements once per tick (`:1474-1476`) and is reset to `0`
whenever the jump key is not held (`:1523-1524`).

### 2.6 Gravity and drag

- Gravity `0.08` (`EntityLivingBase.java:1283`), then `motionY *= (double)0.98f` (`:1284`).
- Horizontal drag: `f4` from step 30/34 (`:1285-1286`).

### 2.7 Momentum threshold

Every tick, before movement: any of `motionX`, `motionY`, `motionZ` with absolute value
below `0.005` is set to `0.0` (`EntityLivingBase.java:1492-1500`). It applies to all
three axes, every tick, on the ground and in the air. There is no jump-specific variant.
The comparison is on the raw `double` motion (already carrying the previous tick's
friction); the MCPK wiki's "Momentum Threshold" page describes the same cutoff for 1.8.9
(`0.005`; `0.003` in 1.9+) and its effect — it truncates the jump apex (see the tick-10
worked example).

### 2.8 Collision order (`Entity.moveEntity`, `Entity.java:553-712`)

1. Web: if `isInWeb`, scale `x*0.25`, `y*0.05`, `z*0.25` and zero all motion
   (`:564-572`).
2. Sneak edge slowdown: if `onGround && isSneaking() && player`, shrink the step until
   the player is over a block (`:576-593`).
3. Collect collisions for the whole swept box over
   `World.getCollidingBoundingBoxes` (`:594`; `World.java:1003`).
4. Apply Y offset first, move on Y (`:595-600`). `flag1` records "was on ground or moving
   down into something" (`:600`).
5. Apply X offset, move on X, then Z offset, move on Z (`:601-608`).
6. Step-up: if `stepHeight > 0`, `flag1` and the horizontal move was shortened, retry the
   move raised by `stepHeight`, pick the smaller of the two displacements, and if the
   raised move still shortens the horizontal displacement, revert to the original
   (`:609-673`).
7. `resetPositionToBB`: `posX`/`posZ` become the midpoint of the box, `posY` becomes
   `minY` (`:756-760`).
8. Set state: `isCollidedHorizontally = d3 != x || d5 != z`,
   `isCollidedVertically = d4 != y`,
   `onGround = isCollidedVertically && d4 < 0.0` where `d3/d4/d5` are the requested
   displacements (`:677-680`).
9. `updateFallState` for fall distance (`:690`; `:828-841`).
10. Zero `motionX` if X was blocked and `motionZ` if Z was blocked (`:691-696`).
11. **If a vertical collision happened, call `block1.onLanded(...)` (`:697-699`). The
    default `Block.onLanded` sets `entity.motionY = 0.0`
    (`net/minecraft/block/Block.java:1125-1127`).**
    This is why a standing player's `motionY` is `-0.0784 = (0 - 0.08) * 0.98f`, not a
    growing negative number.

The offset math inside `AxisAlignedBB` (`net/minecraft/util/AxisAlignedBB.java`):
`offset` `:106-108`; `calculateXOffset` `:113-127`; `calculateYOffset` `:132-146`;
`calculateZOffset` `:151-165`. Each returns the requested offset clamped to the distance
to the block face, and is a no-op unless the boxes overlap on the other two axes (strict
inequalities: touching faces do not block).

### 2.9 Ladder, water, lava, flying

Cited but **not exercised by the current trace**:

- Ladder: `EntityLivingBase.java:1265-1282`.
- Lava branch: `EntityLivingBase.java:1287-1298` (`moveFlying(..., 0.02f)`, then all
  motion `* 0.5` (double), then `motionY -= 0.02`, plus a `motionY = 0.3f` pop-out when
  collided while submerged, `:1295-1297`).
- Water branch: `EntityLivingBase.java:1299-1323` (depth-strider aware `f1`/`f2`,
  `motionY *= (double)0.8f`, `motionY -= 0.02`, pop-out `0.3` at `:1320-1322`).
- Flying: `EntityPlayer.moveEntityWithHeading` (`EntityPlayer.java:1419-1435`) and the fly
  key handling in `EntityPlayerSP.onLivingUpdate` (`:619-642`).

### 2.10 Cross-checks against the MCPK wiki

The wiki (https://www.mcpk.wiki) is the community reference for these formulas. The
checks below are *behaviour-level*: the code is the ground truth, the wiki claims must
agree with it.

- Vertical: https://www.mcpk.wiki/wiki/Vertical_Movement_Formulas — jump `0.42`, then
  `V_Y,t = (V_Y,t-1 - 0.08) * 0.98`. Matches `EntityLivingBase.java:1223,1283-1284`.
- Momentum threshold: https://www.mcpk.wiki/wiki/Momentum_Threshold — the `0.005` cutoff
  for 1.8.9. Matches `EntityLivingBase.java:1492-1500`; the wiki writes the test on the
  velocity component after the previous tick's friction, which is exactly the raw
  `motion*` the code tests at the start of the next tick (the friction of
  `:1284-1286` already ran at the end of the previous tick).
- Horizontal: https://www.mcpk.wiki/wiki/Horizontal_Movement_Formulas — acceleration
  `0.1 * M * E * (0.6/S)^3` with `M = 1.3` while sprinting, air acceleration `0.02 * M`,
  and the sprint jump boost `+0.2` along the heading. Matches 2.2, 2.3 and 2.5:
  `getAIMoveSpeed() = 0.1 * 1.3`, `f ≈ (0.6/S)^3`, `jumpMovementFactor = 0.02 * 1.3`.
- Slipperiness: https://www.mcpk.wiki/wiki/Slipperiness — 0.6 normal, 0.98 ice, 0.8
  slime. Values must still be loaded from `blocks.json`, not typed in (section 4).

**External oracles.** ParkourCalculator (Leg0shii, GPL-3.0) and MPK are behaviour-only
oracles per ADR 0009 — no source was read and nothing was copied for these notes. A
quantitative cross-check against a running ParkourCalculator has **not** been done
(**UNVERIFIED**); the trace itself is the strongest check so far.

---

## 3. Worked example

Source data: `loader-1.8.9/run/kairos/traces/sprintjump_flat_01_run01.jsonl`.
Scenario: 5 ticks of sprint-forward, 1 tick of sprint-forward+jump, then 12 ticks of
sprint-forward, on flat stone with the player starting at `(0.5, 65.0, 0.5)`, yaw `0`,
pitch `0`. Anchor line (`tick = -1`): `vel = (0, -0.0784000015258789, 0)`, on ground.

Every tick below was reproduced by a throwaway float-exact checker (same expressions as
the code, `float`/`double` exactly as written, `MathHelper`'s table initialization
copied). It reproduces **all 18 ticks bit-for-bit** (identical `Double.toString` output
for `pos`, `vel`, flags and `te`); the ticks shown here are the interesting ones. Note
that the trace mixes `float` and `double` constantly; the "clean" decimal stories
(`0.546`, `0.13`, `0.026`, `0.98`) are only the first few digits of what the game stores.

Constants for every tick, yaw = `0.0f` (table values, `MathHelper.java:29-38,496-498`):

- `sin(0.0f) = 0.0f`, `cos(0.0f) = 1.0f`.
- Ground friction: `f4 = 0.6f * 0.91f = 0.54600006f` (`0.546000063419342` as a double).
- `f = 0.16277136f / f4^3 = 0.9999998f`.
- Sprint speed attribute: `(float)((double)0.1f * (1.0 + 0.30000001192092896)) = 0.13000001f`
  (`0.1300000101327896`). Ground `f5 = 0.13000001f * 0.9999998f = 0.12999998f`.
- Input axis after scaling: `moveForward = 1.0f * 0.98f = 0.98f`. With `len` clamped to
  `1`, `moveFlying` adds `(double)(0.98f * f5)` in `+Z` (since `sin = 0`, `cos = 1`).
- Air friction: `(double)0.91f = 0.9100000262260437`; air `f5 = jumpMovementFactor`.

### Tick 0 — sprint starts, first movement

Start (recorded end of tick -1): `pos = (0.5, 65.0, 0.5)`,
`vel = (0, -0.0784000015258789, 0)`, on ground, not sprinting. Keys: forward, sprint.

- Sprint start fires (`EntityPlayerSP.java:606-611`): speed attribute becomes 1.3×
  (section 1.4). The previous tick's `moveForward` was `0`, so `!flag2` holds and the
  sprint key path is taken.
- Velocity: `motionZ` starts at `0`; `moveFlying` adds
  `(double)(0.98f * 0.12999998f) = 0.1273999810218811`.
- `moveEntity` moves into open air horizontally; the floor clamps the fall: the Y offset
  is clamped to `0.0` and `onLanded` zeroes `motionY` before gravity runs.
  - `posZ = 0.5 + 0.1273999810218811 = 0.6273999810218811` ✔ trace `0.6273999810218811`
    (through the box midpoint: `(0.19999998807907104 + 0.9273999929428101) / 2`).
  - `posY = 65.0` ✔; `onGround = true` ✔ (`isCollidedVertically = true` from the clamp).
- Gravity: `motionY = (0.0 - 0.08) * (double)0.98f = -0.0784000015258789` ✔.
- Friction: `motionZ = 0.1273999810218811 * (double)0.54600006f = 0.06956039771757005` ✔.
- Tail (`EntityPlayer.java:534-536`): `jumpMovementFactor = 0.025999999f`
  (`0.025999998673796654`), used from the next tick.

### Tick 5 — jump, with the still-on-ground acceleration

Start (end of tick 4): `pos = (0.5, 65.0, 1.581978028176667)`,
`vel = (0, -0.0784000015258789, 0.1457819489355333)`, on ground, sprinting.
Keys: forward, jump, sprint.

- Jump fires (`jumpTicks == 0`, `onGround`): `motionY = 0.41999998688697815`.
- Sprint-jump boost (`EntityLivingBase.java:1227-1231`):
  `motionZ += (double)(1.0f * 0.2f) = 0.20000000298023224`
  → `motionZ = 0.1457819489355333 + 0.20000000298023224 = 0.34578195191576555`.
- Still on ground during `moveEntityWithHeading` (the jump clears the flag inside
  `moveEntity`), so the **ground** acceleration applies: adds
  `(double)(0.98f * 0.12999998f) = 0.1273999810218811`
  → `motionZ = 0.47318193293764665`.
- `moveEntity`: nothing blocks the rise, so:
  - `posY = 65.0 + 0.41999998688697815 = 65.41999998688698` ✔ trace
    `65.41999998688698` (reset to `minY`).
  - `posZ = 1.581978028176667 + 0.47318193293764665 = 2.0551599611143136` ✔ trace.
  - `onGround = false` ✔ (`d4 == y` vertically, no collision).
- Gravity: `(0.41999998688697815 - 0.08) * 0.9800000190734863 = 0.33319999363422365`
  ✔ trace.
- Friction: `0.47318193293764665 * 0.546000063419342 = 0.25835736539284193` ✔ trace.

### Tick 6 — first air tick, sprinting

Start (end of tick 5): `vel = (0, 0.33319999363422365, 0.25835736539284193)`,
`pos = (0.5, 65.41999998688698, 2.0551599611143136)`, airborne.

- Air acceleration: `f5 = jumpMovementFactor = 0.025999999f` (`0.025999998673796654`),
  so `moveFlying` adds `(double)(0.98f * 0.025999999f) = 0.0254799984395504`
  → `motionZ = 0.28383736383239233`.
- `moveEntity`: no collision. `posY = 65.41999998688698 + 0.33319999363422365 =
  65.7531999805212` ✔ trace; `posZ = 2.338997324946706` ✔ trace.
- Gravity: `(0.33319999363422365 - 0.08) * 0.9800000190734863 = 0.24813599859094576`
  ✔ trace.
- Friction (air): `0.28383736383239233 * 0.9100000262260437 = 0.2582920085314081` ✔
  trace `0.2582920085314081`. Note how close the per-tick additions and the 0.91 drag
  are: `f5 = 0.026` and `1 - 0.91 = 0.09` make the horizontal speed converge to
  `0.026 * 0.91 / 0.09 ≈ 0.263` asymptotically — the trace is already near it.

### Tick 10 — the momentum threshold fires

Start (end of tick 9): `vel = (0, 0.0030162615090425808, 0.2581291607260307)`,
`pos = (0.5, 66.24918707874468, 3.1901402743482707)`, airborne.

- `abs(motionY) < 0.005` is true, so the threshold sets `motionY = 0.0`
  (`EntityLivingBase.java:1495-1497`), **before** anything moves.
- So the player does not move vertically this tick: `posY` stays
  `66.24918707874468` ✔ trace (tick 10 and tick 9 have the same `posY`).
- Horizontal is unaffected: adds `0.0254799984395504` → `motionZ = 0.2836091591655811`;
  `posZ = 3.4737494335138517` ✔ trace.
- Gravity: `(0 - 0.08) * 0.98f = -0.0784000015258789` ✔ trace.
- Friction: `0.2836091591655811 * 0.9100000262260437 = 0.25808434227862503` ✔ trace.

Observation: without the threshold the player would have risen another `0.003016…`
(`posY = 66.25220334025372`); the threshold truncates the apex at `66.249187…`
(height `1.249187…`). This is the rule MCPK's momentum-threshold page documents.

### Tick 16 — landing

Start (end of tick 15): `pos = (0.5, 65.10408037809304, 4.891198373469491)`,
`vel = (0, -0.44749789698341763, 0.2579139670544474)`, airborne.

- Horizontal as usual: adds `0.0254799984395504` → `motionZ = 0.2833939654939978`.
- `moveEntity`: requested Y move `-0.44749789698341763` would take `minY` to
  `64.65658248…`, below the floor top `65.0`, so the Y offset is clamped to
  `65.0 - 65.10408037809304 = -0.10408037809304000…`:
  `posY = 65.0` ✔; `isCollidedVertically = true`, `onGround = true` ✔;
  `onLanded` sets `motionY = 0.0`.
  `posZ = 5.174592338963489` ✔ trace.
- Gravity: `-0.0784000015258789` ✔ trace.
- Friction: `f4` was recomputed (`:1261-1264`) **before** `moveEntity`, while the player
  was still airborne, so the air value applies on the landing tick:
  `0.2833939654939978 * 0.9100000262260437 = 0.25788851603184054` ✔ trace.

### Tick 17 — first full ground tick after landing

Start (end of tick 16): `pos = (0.5, 65.0, 5.174592338963489)`,
`vel = (0, -0.0784000015258789, 0.25788851603184054)`, on ground, sprinting.

- Ground `f4`/`f`/`f5` again: adds `0.1273999810218811`
  → `motionZ = 0.38528849705372165`.
- `moveEntity`: floor clamps again; `posY = 65.0` ✔; `posZ = 5.559880836017211` ✔ trace.
- Friction: `0.38528849705372165 * 0.546000063419342 = 0.210367543826075` ✔ trace.

---

## 4. Not understood yet / untested

- **Server corrections.** In singleplayer the client player's physics is authoritative,
  but the integrated server still validates: `NetHandlerPlayServer.processPlayer`
  re-runs the move and sends `S08PacketPlayerPosLook` on "moved too quickly"
  (`net/minecraft/network/NetHandlerPlayServer.java:307-312`), "moved wrongly"
  (`:331-345`) or a >20-tick gap (`:366-368`), via `setPlayerLocation` (`:373-401`).
  `NetHandlerPlayClient.handlePlayerPosLook` then zeroes the motion components not sent
  as relative (`net/minecraft/client/network/NetHandlerPlayClient.java:536-557`) and
  teleports. The trace records only end-of-tick state (ADR 0010), so such a correction
  would appear as an unexplained position jump, not as an event. **UNVERIFIED** that it
  never happens in practice.
- **Slipperiness table.** The formulas above need each block's `slipperiness`. Values are
  not listed here; they must come from the generated block data (`blocks.json` dumped by
  the loader), not from memory. That data does not exist in this repo yet. **UNVERIFIED**
  until it does.
- **Ladder / water / lava / flying** branches are cited but not exercised by
  `sprintjump_flat_01_run01.jsonl`. Treat them as untested; Phase 1 needs traces.
- **Push and entity collisions.** `pushOutOfBlocks` is called at
  `EntityPlayerSP.java:601-604` before sprint decisions, and `collideWithNearbyEntities`
  runs only on the server (`EntityLivingBase.java:1533-1537`). Both can move the player
  without an input. Not modelled, not seen in the trace.
- **`newPosRotationIncrements`.** The interpolation branch at
  `EntityLivingBase.java:1477-1486` is for remote entities; it was never non-zero on the
  client player in this trace. **UNVERIFIED** whether it can be non-zero locally.
- **`isMovementBlocked()`** is overridden for players to return true when health is
  `<= 0.0f` or the player is sleeping (`EntityPlayer.java:463-466`); the base
  `EntityLivingBase` condition is health `<= 0.0f` (`EntityLivingBase.java:1174-1176`).
- **`stepHeight` step-up** (`Entity.java:609-673`) is quoted but no example was verified.
- **The `MathHelper` table values.** `sin`/`cos` depend on the exact table initialisation
  (`MathHelper.java:496-498`). The checker used the same formula and matched to the last
  digit here, but Java-8-vs-newer `float` rounding differences are not proven impossible.
- **`float`/`double` liability.** The trace mixes `float` intermediates (acceleration,
  `moveFlying` products) with `double` state. Reproducing trace numbers digit-for-digit
  requires the exact expression shapes quoted here, widened `float` constants included.
  This was demonstrated for all 18 ticks of this trace only.
- **Ambient randomness in the world.** `EntityPlayer.onLivingUpdate` also refills food
  on Peaceful (`EntityPlayer.java:519-526`) and `World` ticks block/weather updates.
  None of them showed up in the trace; they are world content, not player physics.
- **Paused frames.** `updateEntities` (and `theWorld.tick`) are skipped while the game is
  paused (`Minecraft.java:1735-1740, 1749-1753`), so no tick is recorded (`te` gap guard in
  `KairosRecorder`).

---

## 5. Index of cited files

| File | What it gives |
|---|---|
| `client/Minecraft.java` | tick loop, when physics runs, input timing |
| `client/renderer/EntityRenderer.java` | mouse look → `setAngles` |
| `client/multiplayer/WorldClient.java` | world tick (time, spawn queue, blocks) |
| `world/World.java` | entity update order, `ticksExisted`, collision box query |
| `entity/Entity.java` | `setAngles`, `moveFlying`, `moveEntity` collision order, `resetPositionToBB` |
| `entity/EntityLivingBase.java` | `jump`, `moveEntityWithHeading`, gravity, threshold, sprint boost, water/lava/ladder |
| `entity/player/EntityPlayer.java` | `jumpMovementFactor`, `getAIMoveSpeed`, fly wrapper, base speed |
| `client/entity/EntityPlayerSP.java` | input reading, sprint decision, `pushOutOfBlocks`, `isServerWorld` |
| `entity/SharedMonsterAttributes.java` | `movementSpeed` attribute definition |
| `entity/ai/attributes/ModifiableAttributeInstance.java` | modifier math (op 2 = ×(1+amount)) |
| `util/MovementInputFromOptions.java` | key → axes, sneak `*0.3` |
| `util/MathHelper.java` | `sin`/`cos` tables, `sqrt_float` |
| `util/AxisAlignedBB.java` | per-axis offset clamping |
| `block/Block.java` | `onLanded` zeroes `motionY` |
| `network/NetHandlerPlayServer.java` | server movement validation and corrections |
| `client/network/NetHandlerPlayClient.java` | `S08` teleport handling |
| `net/minecraftforge/fml/common/FMLCommonHandler.java` | `ClientTickEvent` START/END posting |
