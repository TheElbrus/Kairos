# Journal

## 2026-10-17 - Task 0.1: ADRs 0001-0009 + AGENTS.md
- Done: nine ADRs on the Appendix C template plus the AI rulebook; corrected the plan's nonexistent 1.13-era "data-generator reports" cross-check and added the ParkourCalculator GPL-3.0 note.
- Surprised: 1.8.9 predates the vanilla data-generator reports, so block cross-checks rest on registry state counts plus per-shape spot-check traces.

## 2026-10-17 - Task 0.2: Gradle multi-module skeleton + CI
- Done: `core` module on Gradle 9.8.1 (checksum-pinned wrapper), Java 8 bytecode via `options.release = 8`, placeholder class + JUnit test, ArchUnit rule "core never depends on net.minecraft.*", CI workflow running `./gradlew :core:test` on Temurin 25.
- Surprised: the wrapper jar checked into the gradle/gradle repo at tag v9.8.1 does not match the officially published 9.8.1 wrapper checksum, so the wrapper must be generated from the distribution; Gradle 9 needs `junit-platform-launcher` explicitly on the test runtime classpath; JDK 25 warns that source/target 8 is obsolete while still supporting it; a `gradlew` committed from Windows lands as mode 100644, so CI fails until `git update-index --chmod=+x gradlew`.

## 2026-10-17 - Task 0.3: minimal Forge 1.8.9 loader
- Done: separate `loader-1.8.9/` build (Gradle 8.8, Loom 0.10.0.5, MCP stable_22) that logs `[Kairos] Kairos 0.1.0 loaded. core PLAN_VERSION=2.0` on startup and registers a P keybind whose chat line echoes `dev.kairos.core.Kairos.PLAN_VERSION` -- proof the core jar is shaded into the mod; `runclient.ps1`/`runclient.sh` work around the broken `runClient` task via `printRunClientCommand`; banner in `run/runclient.log` and the in-game chat line verified in the dev client.
- Surprised: Loom launches 1.8.9 through `net.fabricmc.devlaunchinjector.Main` with no game args, so the manual runner only needs jvmargs + classpath; the foojay-provisioned JDK 8 lands at `.gradle-home/jdks/temurin-8-amd64-windows/jdk8u504-b01/` (glob, do not hardcode the patch version); FML logs a missing-version warning unless `@Mod` carries an explicit `version` even when it sits in `mcmod.info` (FMLModContainer.java:196).

## 2026-10-17 - Task 0.4: trace recorder + frozen trace format v1
- Done: ADR 0010 freezes the JSONL format (loader-1.8.9/run/kairos/traces/<name>.jsonl, UTF-8, `\n`, relative `te`, gap guard); `core` gained `dev.kairos.core.trace` (TraceInput/TraceLine/TraceWriter, NaN/Infinity rejected, floats never widened) with byte-pinning tests (9 core tests green incl. ArchUnit); loader records at `ClientTickEvent.END` via `KairosRecorder` with client-side `/kairos record start|stop|status`; live 688-line trace (1 anchor + 687 ticks, 431 of them jumping, 432 sprinting) validated: fixed key order on all lines, `te = tick + 1`, zero CR bytes, gap guard never fired.
- Surprised: `:core:test` does not rebuild the core jar, so the loader (separate build) first compiled against a stale jar missing `dev.kairos.core.trace` -- run `:core:jar` (or `:core:build`) before building the loader; also FML posts `ClientTickEvent` from `onPreClientTick`/`onPostClientTick` (FMLCommonHandler.java:331/:336), which `Minecraft.runTick()` calls at :1456/:1777 around `updateEntities()` (:1739) exactly as the recorder's END-phase assumption requires.
