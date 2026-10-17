# ADR 0005: Determinism rules

Date: 2026-10-17
Status: accepted

Decision:
1. Mirror the game's numeric types exactly (float vs double) at every step, including its sine/cosine implementation for facing angles (copy the same table generation if the game uses one). Never substitute `Math.sin`.
2. `StrictMath` is allowed only for the planner's own math, where the game does not dictate the function.
3. No `HashMap` iteration order in any search path; use ordered or stable structures.
4. Seeded RNG everywhere; no unseeded randomness and no dependence on wall-clock time or thread scheduling.
5. Sprint state and jump delay are part of the player state; they affect physics and must be simulated and recorded.
6. Same state + same inputs = same outputs, per tick, forever. Traces compare byte-for-byte across runs (20-run determinism check at Gate 0).

Reason:
The whole project rests on determinism. Any deviation between sim and game is a sim bug, and any nondeterminism inside the sim makes bugs unfindable and traces unusable as ground truth.

Alternatives considered:
- Double-precision everywhere for simplicity: rejected; the game deliberately mixes float and double, and the difference shows at tight tolerances.
- `Math.sin` instead of the game's implementation: rejected; produces different facing vectors, visible in traces at non-axis-aligned yaw (Phase 1 task 1.6).
- Unordered collections in search for speed: rejected; iteration order leaks into plan tie-breaking, making plans irreproducible.

Consequences:
- Numeric fidelity is an explicit work item: traces at non-axis-aligned yaw such as 33.3 and 161.9.
- Any nondeterminism found (initial sprint state, jump delay, entity pushing, chunk-load timing, world settings) blocks all other work until fixed (Gate 0).
