# ADR 0007: Accuracy tolerance

Date: 2026-10-17
Status: accepted

Decision:
"Match" means exact: a replayed trace must equal the recorded trace tick for tick, with position and velocity compared exactly as recorded (max position error 0 expected). The metric is max position error over all golden and fuzz traces.

The only permitted fallback is a documented per-field tolerance below 1e-9, and only when a proven Java/JVM difference (not a sim bug) forces it. Any such use must be recorded in this ADR with the exact cause and scope.

Golden and fuzz traces are never edited, and tolerances are never loosened to make a failing test pass.

Reason:
The planner solves tight jumps where any visible mismatch is a missing or wrong physics rule. Hiding it behind a tolerance hides the bug that would later make the bot fail in the real game. Exact comparison turns every mismatch into a precise bug report: first diverging tick and field.

Alternatives considered:
- Approximate matching (for example 1e-6) from day one: rejected; it masks ordering bugs that matter exactly at tight-jump scale.
- No fallback at all: tempting, but if a conforming JVM genuinely changes floating-point results, a documented fallback avoids chasing a phantom. It stays closed until proven necessary.

Consequences:
- The trace comparator reports the first divergent tick and field; that report is the bug report.
- Every mismatch becomes a regression test before it is fixed (PLAN.md section 6).
- If the fallback is ever used, it is recorded here with cause and scope and re-checked at every gate.
