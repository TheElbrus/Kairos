# Loader credits

This loader scaffold is adapted from:

- **lineargraph/Forge1.8.9Template** -- https://github.com/lineargraph/Forge1.8.9Template
- Commit used: `551b1de7c028457dca6a8f8b48fb8b343efbbb42` (2026-02-10)
- Licence: **The Unlicense** (public domain), alternatively CC0 1.0.
  The full text is copied verbatim to `LICENSE` in this folder.

The template's own README states: *"This template is licensed under the Unlicense ...
or alternatively under Creative Commons 1.0 Universal (CC0 1.0) ... your mod, based on
this template can be licensed whatever way you want, and does not need to reference back
to this template in any way."*

The Kairos loader was also ported from the owner's earlier 1.8.9 loader
(`DoingParkour/pkbot`), which came from the same template; the layout, the
`runclient` workaround and the toolchain pins carry over.

## What we changed

- **Removed:** mixins (`AutoDiscoveryMixinPlugin`, `MixinGuiMainMenu`, the mixin config,
  the mixin/annotation-processor dependencies) and **DevAuth**. The skeleton needs neither,
  so they are not dependencies of this project.
- **Removed:** the template's `.github/workflows/init.yml`, which auto-commits and pushes
  to the repository. We do not want automation that writes to a repo.
- **Changed:** mod id `examplemod` -> `kairos`, package `com.example` -> `dev.kairos.loader`.
- **Added:** shading of the repo's own `core` jar into the mod jar.
- **Pinned:** essential Loom `0.10.0.5` (the reference build used `0.10.0.+`).

## What we kept

- essential Loom (`gg.essential.loom`) and architectury-pack200, as the chosen
  toolchain for 1.8.9.
- Shadow (`com.github.johnrengelman.shadow` 8.1.1) for bundling.
- MCP `stable_22` and Forge `1.8.9-11.15.1.2318-1.8.9`.
