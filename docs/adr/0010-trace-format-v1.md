# ADR 0010: Trace format v1 (recorder output)

Date: 2026-10-17
Status: accepted

Decision:

The recorder writes one JSON object per line (`.jsonl`) to
`loader-1.8.9/run/kairos/traces/<name>.jsonl`, UTF-8, `\n` line endings on every OS so
two runs can be compared byte for byte.

**Semantics are unchanged from PLAN.md 3.1 / ADR 0001:** line `N` records the input
applied during tick `N` and the player state at the END of tick `N`.

- `tick` starts at `0`.
- One extra line with `"tick":-1` is written first, on the first `ClientTickEvent.START`
  after recording is enabled. It holds the state at that moment with every `in` value
  zero, so the simulator has a start state for tick 0.

Shape (key order is fixed and asserted by the core test):

```json
{"v":1,"mc":"1.8.9","scenario":"test1","tick":0,
 "in":{"w":1,"a":0,"s":0,"d":0,"jump":1,"sneak":0,"sprint":1,"yaw":0.0,"pitch":0.0,"mf":1.0,"ms":0.0},
 "pos":[0.5,65.0,0.5],"vel":[0.0,0.0,0.0],
 "onGround":true,"sprinting":true,"using":false,"colH":false,"colV":false,"te":1}
```

Field meanings:

| Field | Meaning |
|---|---|
| `v` | format version, currently `1` |
| `mc` | Minecraft version string, `"1.8.9"` |
| `scenario` | the scenario's BASE name (e.g. `sprintjump_flat_01`), never the run index |
| `tick` | tick index, `-1` for the start-state line |
| `in.w` / `in.s` | sign of `movementInput.moveForward` (`>0` / `<0`); both `0` if neither or both keys are held |
| `in.a` / `in.d` | sign of `movementInput.moveStrafe` (`>0` / `<0`); left is POSITIVE (see below) |
| `in.jump` / `in.sneak` | `movementInput.jump` / `movementInput.sneak` |
| `in.sprint` | the sprint KEY state (`keyBindSprint.isKeyDown()`) -- an INPUT |
| `in.yaw` / `in.pitch` | `Entity.rotationYaw` / `rotationPitch`, floats |
| `in.mf` / `in.ms` | `movementInput.moveForward` / `moveStrafe` as floats, read at END of tick |
| `pos` / `vel` | `posX,posY,posZ` / `motionX,motionY,motionZ`, doubles |
| `onGround` | `Entity.onGround` |
| `sprinting` | the entity's sprint STATE (`isSprinting()`), not the key |
| `using` | `isUsingItem()` |
| `colH` / `colV` | `isCollidedHorizontally` / `isCollidedVertically` |
| `te` | ticks since the anchor: `0` on the `tick:-1` line, then `1`, `2`, ... (NOT the raw `ticksExisted`, so two runs compare byte for byte) |

Why `in.sprint` and top-level `sprinting` are both present: they are different things.
`in.sprint` is the raw key (an input the planner must supply); `sprinting` is the
resulting entity state, which sprint-toggle and the food/hunger/collision rules in
`EntityPlayerSP.onLivingUpdate` can leave different from the key.

Why `in.mf` / `in.ms` carry the axes as floats as well as the `w/a/s/d` signs:
`w/a/s/d` cannot express the scaling that the game applies on top of the raw keys.
`MovementInputFromOptions.updatePlayerMoveState()` multiplies both axes by `0.3` when
sneaking (`mc-src/net/minecraft/util/MovementInputFromOptions.java:39-40`), and
`EntityPlayerSP.onLivingUpdate()` multiplies them by `0.2` when using an item
(`mc-src/net/minecraft/client/entity/EntityPlayerSP.java:596-599`). Locating the raw keys
alone would make replay non-exact while using an item; `mf`/`ms` are the post-scaling
values, i.e. exactly what `EntityLivingBase.onLivingUpdate()` feeds to
`moveEntityWithHeading(this.moveStrafing, this.moveForward)`
(`mc-src/net/minecraft/entity/EntityLivingBase.java:1528-1531`, after the `* 0.98f` there).

Left is positive strafe, verified in
`MovementInputFromOptions.updatePlayerMoveState()`:
`keyBindLeft.isKeyDown()` -> `moveStrafe += 1.0f` (line 31) and `keyBindRight` ->
`moveStrafe -= 1.0f` (line 34); forward/back are `moveForward += 1.0f` (line 25) /
`-= 1.0f` (line 28).

Numbers:
- Doubles are written with `Double.toString`, floats with `Float.toString` -- full
  round-trip precision, never rounded, and floats are never widened to double first
  (a widened `33.3f` would print a different decimal than the float the game used).
- `NaN` and `Infinity` are not valid JSON, so `TraceWriter` throws
  `IllegalArgumentException` rather than emitting them.

Run index lives only in the file name:
- A `runmany` batch (task 0.5) writes one file per run, `<base>_runNN.jsonl`, but every
  line in every file carries the same `scenario` value, the batch's base name. Stamping
  the run index into `scenario` would make the very first line differ between runs and
  break the byte-for-byte determinism check for a reason that is not physics.
  `KairosRecorder.start` therefore takes the output file name and the scenario label as
  two separate values.
- `/kairos record start <name>` uses the same value for both, so a hand-recorded trace is
  unchanged.

Relative `te`:
- `te` is relative to the anchor line, which is `0`; the line for tick `N` carries
  `N + 1`. The recorder captures the absolute `ticksExisted` at the anchor and subtracts
  it. This is what makes two runs of one scenario byte identical -- the absolute value
  depends on how long the world has been open.
- The gap guard still compares the raw absolute `ticksExisted`, because it only has to
  detect that a tick was skipped, not compare across runs.

Gap guard (determinism):
- If the absolute `ticksExisted` does not increase by exactly 1 between consecutive
  recorded ticks, recording stops, the file is closed, and a red chat message is sent
  ("player was not ticked: trace invalid"). No bad line is written.
- The recorder runs on `ClientTickEvent.END` (`FMLCommonHandler.java:336`), which
  `Minecraft.runTick()` reaches after `theWorld.updateEntities()` ran the player's tick
  (`Minecraft.java:1739` pre-`onPostClientTick`, `:1777`). `Phase.START` would still hold
  the previous tick's end state.

First-trace expectations (behaviours reproduced from the reference implementation):
- The `tick:-1` anchor carries `in.yaw = 0.0` and `in.pitch = 0.0`, even though the real
  yaw/pitch at that moment differ. This is intentional and harmless: every later line
  carries its own absolute `in.yaw`/`in.pitch`, so the anchor's zeroed angles are never
  used as the replay rotation.
- `in.sprint` (the raw key) and the top-level `sprinting` (the entity state) usually agree,
  but the two fields are kept separate because toggle-sprint and the food/collision rules
  can make them disagree on later ticks.
- `te` advances by exactly 1 on every line while the guard holds.

Known hazard, deliberately not handled:
- Server position corrections are NOT detected. In singleplayer the client player runs
  its own physics (`EntityPlayerSP.isServerWorld()` returns `true`,
  `EntityPlayerSP.java:443-444`) and the server's "moved too quickly" check is disabled
  for the singleplayer owner (`NetHandlerPlayServer.java:308-310`), but
  `NetHandlerPlayServer.processPlayer` can still call
  `setPlayerLocation` -> `S08PacketPlayerPosLook` (`NetHandlerPlayServer.java:373`,
  `:377`, send at `:400`), and `NetHandlerPlayClient.handlePlayerPosLook` then
  hard-overwrites `posX/posY/posZ` and zeroes `motionX/Y/Z`
  (`NetHandlerPlayClient.java:528-557`, zeroing at `:539`, `:544`, `:549`).
  Detecting this would need mixins or packet hooks, which ADR 0002 rules out. The Gate 0
  determinism test and the Phase 1 sim comparison are expected to expose it.

Reason:
- The trace is the contract between the game and the simulator (PLAN.md 3.1, 5.1). It
  must be complete enough to replay a tick exactly, and byte-stable so two runs can be
  compared without a parser.

Alternatives considered:
- Recording raw key booleans only (`w/a/s/d` as 0/1): rejected, cannot express the
  `0.3` / `0.2` scaling above, so replay would drift whenever an item is in use.
- Recording only the effective axes (`mf`/`ms`) and dropping `w/a/s/d`: rejected, the
  planner needs to know which keys to press, not just the resulting axes.
- Rounding numbers to a fixed number of decimals: rejected, breaks exactness (ADR 0005).

Consequences:
- PLAN.md section 5.1 documents this shape and defers to this ADR for the field table.
- The core test pins the exact output for a fixed input, including `0.1 + 0.2`,
  `-0.0` and the float `33.3f`.
- Trace comparison must parse numbers, not compare strings, because
  `Double.toString`/`Float.toString` output can differ between JDK versions for some
  values (Java 19+ switched to the shortest round-trip algorithm, JDK-4511638). The test
  therefore only uses values whose strings are identical on Java 8 and on modern JDKs.
