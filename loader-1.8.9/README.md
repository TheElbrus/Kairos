# loader-1.8.9

The thin Forge 1.8.9 mod for Kairos (mod id `kairos`).

Adapted from **lineargraph/Forge1.8.9Template** (The Unlicense) -- see `CREDITS.md`.

## Why this is a SEPARATE Gradle build

The repo root build uses Gradle 9.8.1 and contains only `core`, so `./gradlew :core:test`
stays fast and never pulls in Minecraft. This loader needs a completely different
toolchain: Gradle 8.8, essential Loom, and a Java 8 toolchain. Mixing them would drag
Minecraft into every root build. So it has its own `settings.gradle.kts` and its own
Gradle wrapper (see ADR 0002).

`core` is handed over as a **jar**: build it at the root first, then this build shades it
into the mod jar (Forge 1.8.9 has no Jar-in-Jar).

## Build and run

Windows (PowerShell):

```powershell
# 1. Build core at the repo root
.\gradlew.bat :core:build

# 2. Build the loader (from this folder). Runs on JDK 21; auto-provisions a JDK 8 for the game.
cd loader-1.8.9
$env:GRADLE_USER_HOME="..\.gradle-home"; .\gradlew.bat build

# 3. Launch the dev client
.\runclient.ps1
```

bash (Git Bash) equivalents:

```bash
./gradlew :core:build
cd loader-1.8.9
GRADLE_USER_HOME=../.gradle-home ./gradlew build
./runclient.sh
```

`GRADLE_USER_HOME=../.gradle-home` keeps all Gradle caches **inside the repo** instead of
writing to `~/.gradle`. Output: `build/libs/kairos-0.1.0.jar` (contains shaded `core`).

### Testing the mod

In the dev client: **Singleplayer -> Create New World -> play -> press `P`**. Chat shows
`[Kairos] keybind works. core PLAN_VERSION=2.0`. The game's run directory is `run/`
(gitignored); the log is `run/runclient.log` and contains
`[Kairos] Kairos 0.1.0 loaded. core PLAN_VERSION=2.0` once the mod initialises.

### Why `runclient.ps1` / `runclient.sh` and not `.\gradlew.bat runClient`

Loom 0.10.0.5 + Gradle 8.8 reject the `runClient` task during validation:
`property 'main' is missing an input or output annotation` (Loom's `RunGameTask` extends
`JavaExec` but exposes an unannotated `getMain()`). The scripts ask the
`printRunClientCommand` task for the launch command and run it directly on the provisioned
JDK 8. Set `KAIROS_PRINT_ONLY=1` to print the command without launching. Details in
`../docs/JOURNAL.md`.

## Status

**Building and running** (task 0.3) and **recording** (task 0.4): the dev client launches,
logs the banner, the P keybind echoes a `core` constant, and `/kairos record start|stop|status`
writes one JSONL trace line per client tick (format frozen in `../docs/adr/0010-trace-format-v1.md`).
**Block dumping** (task 1.0; schema 2 since task 1.4): `/kairos dumpblocks` writes
`run/kairos/blocks.json` (see the `BlockDumper` class comment) -- every registered block
state with its collision boxes, slipperiness and climbable flag, the generated connector
facts (`connector` kind/group/cube/pane) and the per-state `solid` mask of
`Block.isSideSolid`, deterministic (LF-only; two consecutive runs print the same SHA-256).
Scenario runs (`/kairos scenario run|runmany`) are task 0.5. The fuzz corpus and batch
harness are task 1.7.1 (`/kairos scenario fuzz`); recording the full 1000-scenario fuzz
set is task 1.7.2.
See `../docs/STATUS.md` and `../docs/JOURNAL.md`.

Recording a trace in the dev client:

```
/kairos record start test1     # refuses to overwrite; writes run/kairos/traces/test1.jsonl
... play ...
/kairos record stop            # gray "stopped ... after N ticks"; red line means the gap guard fired
```

Building a scenario and recording it:

```
/kairos scenario run test1            # builds data/scenarios/test1.json, records test1.jsonl
/kairos scenario runmany test1 3      # records test1_run01..03.jsonl; refuses unless all 3 are byte-identical
```

Fuzzing (task 1.7.1 harness; the corpus is `data/fuzz/scenarios.jsonl`, 1000 deterministic
scenarios, 100 ticks each):

```
/kairos scenario fuzz 3               # records fuzz_0000..fuzz_0002, one trace each
/kairos scenario fuzz 1000 resume     # continue after the last existing fuzz_NNNN trace
```

`fuzz` parses and resolves every requested scenario before touching the world, refuses to
overwrite an existing `<scenario>.jsonl` unless `resume` is given, and aborts with the
scenario name/index on the first failure (already recorded traces stay on disk). This is a
development tool: the corpus and harness are committed, the fuzz traces are not.

Dumping the block registry:

```
/kairos dumpblocks             # gray "blocks=N states=M -> .../run/kairos/blocks.json SHA-256 <hex>"
```

Minecraft APIs (key binding, client tick, chat, commands, movement input) are **cited from
`mc-src/`** (MCP `stable_22`, gitignored); nothing here uses an API from memory.
