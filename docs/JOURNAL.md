# Journal

## 2026-10-17 - Task 0.1: ADRs 0001-0009 + AGENTS.md
- Done: nine ADRs on the Appendix C template plus the AI rulebook; corrected the plan's nonexistent 1.13-era "data-generator reports" cross-check and added the ParkourCalculator GPL-3.0 note.
- Surprised: 1.8.9 predates the vanilla data-generator reports, so block cross-checks rest on registry state counts plus per-shape spot-check traces.

## 2026-10-17 - Task 0.2: Gradle multi-module skeleton + CI
- Done: `core` module on Gradle 9.8.1 (checksum-pinned wrapper), Java 8 bytecode via `options.release = 8`, placeholder class + JUnit test, ArchUnit rule "core never depends on net.minecraft.*", CI workflow running `./gradlew :core:test` on Temurin 25.
- Surprised: the wrapper jar checked into the gradle/gradle repo at tag v9.8.1 does not match the officially published 9.8.1 wrapper checksum, so the wrapper must be generated from the distribution; Gradle 9 needs `junit-platform-launcher` explicitly on the test runtime classpath; JDK 25 warns that source/target 8 is obsolete while still supporting it.
