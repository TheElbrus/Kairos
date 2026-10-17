# ADR 0003: Language

Date: 2026-10-17
Status: accepted

Decision:
Java for both `core` and the Forge mod. Python only for analysis scripts under `tools/`, never in the physics, simulator, or test path.

Reason:
- The simulator must mirror the game's exact float/double semantics and Math behavior. Running on the JVM gives the strongest guarantee of matching Minecraft's numerics (same primitive semantics, `StrictMath` available, one source of rounding rules).
- `core` must be a pure Java library testable with JUnit in seconds without launching the game (PLAN.md section 4.1).
- The Forge 1.8.9 mod must be Java anyway.
- Python is convenient for one-off analysis (parsing traces, plotting metrics) and stays out of the critical path so it can never influence physics results.

Alternatives considered:
- Python core: far too slow for millions of simulated ticks, and cannot reproduce Java float/double and `Math` behavior reliably.
- C++/Rust core behind JNI: fastest, but adds a second toolchain, build, and FFI complexity for zero v1 benefit; an allocation-free Java sim meets the plan's performance bar (section 4.4).
- Kotlin: fine on the JVM, but the Forge 1.8.9 tutorials and ecosystem are Java-first.

Consequences:
- `core/` and `loader-1.8.9/` are Java only.
- `tools/` may use Python (or Java if genuinely needed), but tooling output is never fed back into physics or test verdicts.
- Performance work uses allocation-free Java patterns with internal scratch buffers, measured in Phase 1 task 1.8.
