# Status

Environment for task 0.2 (exact versions used; CI reproduces the JDK):

- JDK: Eclipse Temurin 25.0.4.1+1 (LTS), 64-bit, Windows (local) / ubuntu-latest (CI)
- Gradle: 9.8.1, via wrapper; distribution `gradle-9.8.1-bin.zip` with pinned sha256 `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`
- JUnit: 5.14.4 (Jupiter, via `junit-bom`) - test scope only
- ArchUnit: 1.5.1 - test scope only
- CI: GitHub Actions, `actions/setup-java@v6` with Temurin 25, runs `./gradlew :core:test`

| Phase | Gate | Status | Tag | Date | Notes |
|---|---|---|---|---|---|
| 0   | checklist | [~] | phase0-done | | 0.1, 0.2 done |
| 0.5 | M1 clip   | [ ] | phase05-done | | |
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
| 0.3 | Mod loads, logs a line, one keybind | [ ] |
| 0.4 | Recorder writes one trace line per tick | [ ] |
| 0.5 | Scenario harness `/Kairos scenario run <name>` | [ ] |
| 0.6 | `physics-notes.md` draft with citations | [ ] |
