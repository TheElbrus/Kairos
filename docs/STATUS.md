# Status

Environment for task 0.2 (exact versions used; CI reproduces the JDK):

- JDK: Eclipse Temurin 25.0.4.1+1 (LTS), 64-bit, Windows (local) / ubuntu-latest (CI)
- Gradle: 9.8.1, via wrapper; distribution `gradle-9.8.1-bin.zip` with pinned sha256 `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`
- JUnit: 5.14.4 (Jupiter, via `junit-bom`) - test scope only
- ArchUnit: 1.5.1 - test scope only
- CI: GitHub Actions, `actions/setup-java@v6` with Temurin 25, runs `./gradlew :core:test`
- Loader (separate build): Gradle 8.8 wrapper, essential Loom 0.10.0.5, MCP `stable_22`, Forge `1.8.9-11.15.1.2318-1.8.9`; Gradle runs on JDK 21 (`~/.jdks/ms-21.0.12.1`), game bytecode on Java 8 via Foojay-provisioned Temurin `8u504-b01` (`.gradle-home/jdks/`)

| Phase | Gate | Status | Tag | Date | Notes |
|---|---|---|---|---|---|
| 0   | checklist | [x] | phase0-done | 2026-10-17 | 0.1-0.6 done, 0.6 reviewed APPROVE; Phase 0 closing |
| 0.5 | M1 clip   | [x] | phase05-done | 2026-10-18 | 0.5.1 FlatSim exact on 3 flat goldens (18/20/25 ticks); 0.5.2 solver: 1-4 block gaps solved (jump ticks 8/12/15/19), 6 refused; 0.5.3 M1: the solver's 4-block-gap plan recorded in game replays bit-exact (GapGoldenTest, 26 tests green); clip kept locally, never committed |
| 1   | checklist | [ ] | phase1-done | | |
| 2   | checklist + gate script | [ ] | phase2-done | | |
| 3   | ./gradlew gate3 | [ ] | phase3-done | | |
| 4   | ./gradlew gate4 | [ ] | phase4-done | | |
| 5   | ./gradlew gate5 | [ ] | phase5-done | | |

Phase 0 tasks (PLAN.md section 7):

| Task | What | Status |
|---|---|---|
| 0.0 | Java/Gradle/modding basics | [ ] |
| 0.1 | ADRs 0001-0009, AGENTS.md | [x] |
| 0.2 | Gradle skeleton + CI (`:core:test`, Java 8 bytecode) | [x] |
| 0.3 | Mod loads, logs a line, one keybind | [x] |
| 0.4 | Recorder writes one trace line per tick | [x] |
| 0.5 | Scenario harness `/Kairos scenario run <name>` | [x] |
| 0.6 | `physics-notes.md` draft with citations | [x] reviewed |

Phase 0.5 tasks (PLAN.md "Phase 0.5: Thin vertical slice"):

| Task | What | Status |
|---|---|---|
| 0.5.1 | Quick-and-dirty sim: flat ground, walk/sprint/jump, air movement, drag, full blocks only | [x] |
| 0.5.2 | Brute-force solver for one straight gap (constant yaw, try every jump-start tick) | [x] |
| 0.5.3 | Play the solved inputs in game through the harness | [x] |
