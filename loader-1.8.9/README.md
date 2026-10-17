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

**Skeleton** (Task 0.3): launches, logs the banner, one keybind echoing a `core` constant.
See `../docs/STATUS.md` and `../docs/JOURNAL.md`.

Minecraft APIs (key binding, client tick, chat) are **cited from `mc-src/`** (MCP `stable_22`,
gitignored); nothing here uses an API from memory.
