# Physics-notes review (task 0.6)

- Reviewer: an independent second model session (separate tool session, read-only repo access).
- Date: 2026-10-17.
- Artifact under review: docs/physics-notes.md (565 lines) at commit 44f1b19.
- Sources used: mc-src/ (MCP stable_22) and the real trace
  loader-1.8.9/run/kairos/traces/sprintjump_flat_01_run01.jsonl.

## Method

1. Line-checked ~70 cited rules against mc-src/ (EntityLivingBase, EntityPlayerSP,
   EntityPlayer, Entity, World, Minecraft, EntityRenderer, MovementInputFromOptions,
   ModifiableAttributeInstance, Block, MathHelper, AxisAlignedBB, FMLCommonHandler).
2. Re-derived the worked-example numbers independently in a different runtime (.NET
   IEEE-754 floats/doubles) from the exact expressions quoted in the notes, and compared
   bit-for-bit with the trace at ticks 0, 5, 6, 16 (f4, f, aimove, f5, jump factor,
   positions and velocities).

## Verdict: APPROVE

No WRONG or UNCITED rules found in the checked set. All numeric checks matched the trace
exactly, including: ground f4 = 0.54600006341934204 (not 0.546); f = 0.9999998f; sprint
aimove = 0.13000001013278961; ground f5 = 0.12999998033046722; air jumpMovementFactor =
0.025999998673796654; the jump tick still using ground acceleration; the tick-10 threshold
truncating the apex; and the landing tick using AIR friction.

Representative checked-and-OK: jump and sprint boost (EntityLivingBase.java:1215-1234);
f4/f/f5 (:1254-1259); friction recompute (:1261-1264); ladder clamps (:1265-1282);
gravity/drag (:1283-1286); threshold (:1492-1500); jumpTicks gating (:1513-1525); input
scale (:1528-1531); server-only pushing (:1533-1537); sprint decision and the
`pushOutOfBlocks` sequence (EntityPlayerSP.java:594-618, :601-604), super chain (:663),
updateEntityActionState (:532-538), isServerWorld (:443-446); sprint attribute modifier
(EntityLivingBase.java:69, :1143-1152) and op-2 math (ModifiableAttributeInstance.java
:150-163); tail jumpMovementFactor/setAIMoveSpeed (EntityPlayer.java:530-538), base speed
(:214), getAIMoveSpeed (:1437-1440), speedInAir (:183-184), moveEntityWithHeading fly
wrapper (:1419-1435); moveEntity web/sneak/step-up/flags/onLanded (Entity.java:564-699),
resetPositionToBB (:756-760), setAngles (:405-413), moveFlying (:976-988); AxisAlignedBB
offsets (touching faces do not block); World snapshot/++ticksExisted/onUpdate (:1481-1491),
updateEntities (:1307); tick order incl. the keyboard-clobber loop (Minecraft.java:993-994,
:1015, :1456, :1561-1563, :1739, :1749-1753, :1777); mouse look (EntityRenderer.java
:849-885); sin/cos table and sqrt (MathHelper.java:29-42, :496-498); Block.onLanded
(Block.java:1125-1127); ClientTickEvent posting (FMLCommonHandler.java:329-337).

## Findings

1. NIT (fixed with this review): section 4 "Paused frames" cited (Minecraft.java:1735-1740);
   updateEntities is guarded there, but theWorld.tick()'s guard is at :1749-1753. Citation
   extended.
2. NOT AUDITED: the section 4 NetHandlerPlayServer/NetHandlerPlayClient lines (:307-312,
   :331-345, :366-368, :373-401; :536-557). They are self-flagged UNVERIFIED in the notes
   and not load-bearing for Phase 1; revisit when server corrections are studied.
   Everything else cited in the notes was re-checked.

## The author's five least-sure claims

1. Java 8 vs modern-JVM float rounding: this review re-derived the trace in a third runtime
   (.NET) with identical results; the Phase 1 CI comparison remains the real test.
2. Unloaded-chunk gravity fallback: code-read only; stays UNVERIFIED.
3. newPosRotationIncrements = 0 locally: plausible, stays UNVERIFIED; watch for unexplained
   position jumps in Phase 1.
4. Entity pushing / server corrections: stays UNVERIFIED; the Phase 1 mismatch hunt will
   expose it.
5. MCPK momentum-threshold equivalence phrasing: cosmetic; no action.

Safe to build Phase 1 on: YES.
