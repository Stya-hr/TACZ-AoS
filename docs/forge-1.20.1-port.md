# Forge 1.20.1 port

This branch targets Minecraft **1.20.1**, **Forge 47.3.0**, and **Java 17**. The release artifact is `tacz_aos-forge-1.20.1-1.0.0-beta.1.jar`.

## Dependencies and defaults

- Required: official [Timeless and Classics Zero](https://modrinth.com/mod/timeless-and-classics-zero) **1.1.8-hotfix**.
- Optional: the supplied **NeoVoxy 0.3.3 Forge 1.20.1 client** jar, with **stable Embeddium 0.3.31**. The `0.3.31-beta.53` build does not satisfy that Voxy jar's dependency range.
- Optional integrations retain Forge versions of Curios, Lightman's Currency, Collectors Album, Champions Unofficial, Touhou Little Maid, ParCool, and Crawl on Demand. See [dependency details](../libs/README.md).
- Original NeoForge integration code is excluded from this build. Reference copies live in `archive/neoforge-1.21.1-rendering/`; the optional Oculus support is retained as described below.

The default sniper whitelist was checked against the pinned official TACZ gun pack:

| Gun | ID |
| --- | --- |
| AWP | `tacz:ai_awp` |
| M95 | `tacz:m95` |
| M700 | `tacz:m700` |
| M107 | `tacz:m107` |
| Kar98k | `tacz:kar98` |
| Springfield 1873 | `tacz:springfield1873` |

Scope IDs and HUD magnification layouts were also checked against that pack. The first five guns retain the configured ballistic overrides; Springfield retains its TACZ parameters. M109 is not part of this official built-in sniper roster.

The active server configuration is `<world>/serverconfig/tacz_aos-server.toml`. The file generated in the instance's `config/` directory is a reference template; copy it into `defaultconfigs/` to establish defaults for newly created worlds. This port does not migrate old configurations.

## Initial-port validation

The following passed on the Forge development runtime:

- **118 unit tests** and **72 baseline server GameTests**.
- **9 optional-integration GameTests** with the actual Forge dependency jars: special loot, maid power points, Curios, and both optional crawling mods.
- A real client without Voxy: scope HUD/layouts, distant entities and AI, smoke rendering and expiry, rangefinder models, and observer feedback.
- A real client with the supplied Voxy and stable Embeddium: **2-chunk vanilla view distance**, target approximately **880 blocks away**, actual exploration/save/rejoin, Voxy depth picking, distant entity tracking and AI, both TACZ targets and their recovery animations, occlusion, and damage after unscoping.
- A supplemental real-client test of ParCool and Crawl on Demand: prone idle sway suppression and restoration on exit.

The initial-port release jar was inspected for Java 17 class versions, Forge metadata, remapped mixin references, 1.20.1 resource paths, and absence of active NeoForge compatibility classes. Only MixinExtras is bundled; optional mods and test classes are not bundled. The release jar has not separately been tested in a normal launcher installation.

## Reproduction

Use a Java 17 installation and the Gradle wrapper. Place the supplied Voxy jar in `libs/` before building.

The build also supports running Gradle on Java 21 while emitting Java 17 bytecode. TACZ's nested libraries are resolved lazily, after Loom configures its repositories. Resolving them during build-script evaluation previously caused `Cannot mutate content repository descriptor 'LoomLocalRemappedMods' after repository has been used` when Java 21 triggered Loom's LWJGL upgrade. After updating the build script, reload the Gradle project in the IDE; deleting dependency caches is not required.

```text
gradlew build
gradlew runGameTestServer
gradlew runGameTestServer -Pgametest_namespaces=tacz_aos_lootcompat -Penable_loot_compat_runtime=true -Penable_maid_compat_runtime=true -Penable_curios_runtime=true -Penable_prone_compat_runtime=true
gradlew runClientScopeTest
gradlew runClientLongRangeTest -Penable_voxy_runtime=true
gradlew runClientScopeTest -Penable_prone_compat_runtime=true -Pprone_sway_test_only=true
```

Client tests create isolated test worlds under `build/` and close automatically. Server GameTests reset only their dedicated `build/gametest-forge/world` directory before each run. Run baseline and optional-integration GameTests separately: third-party mods can legitimately add drops to baseline fixtures.

## Entity observation and section occlusion

Entity subscriptions, native pairing/repair, pose interpolation, ballistic prediction and chunk retention retain the initial Forge architecture. Visual occlusion is now a separate shared-section pipeline; the old per-entity boxes and CPU model-polygon clipping have been removed. The network protocol is `forge-11`: update clients and server together.

- Each observer has a bounded union of required 16x16x16 sections, shared by all observed entities. Mandatory coverage follows the actual rendered eye-to-entity corridor with a small model margin, independent of crosshair ordering. A larger all-direction preparation envelope is optional and does not gate visibility. It is clipped to each entity's 5x5 chunk neighbourhood and the configured radius (default 96 blocks). It is **not** complete coverage of an arbitrarily long sight line.
- Only resident server sections are read. No new chunk tickets, disk terrain reads, full-flight-path scans or persistent snapshots are introduced.
- Section scans resume across updates. A new version is published only after all 4096 cells are classified; unchanged sections do not resend geometry. Empty resident sections have a fast path. Previously completed geometry remains in use during a refresh. Missing initial sections or admission-budget overflow defer the affected extra entity's rendering, but do not stop its server simulation.
- Clients cache greedy exterior faces for complete section versions. Grass and crops use baked model texture alpha; leaves, glass, slabs, stairs, doors and other solid/complex blocks use full cells. Air, fluids, fire and invisible utility blocks provide no cover.
- A shared temporary GPU depth pass clips the extra entity batch. It bypasses Oculus material substitution, writes no scene colors and restores invisible mask depth after rendering, preserving nearer entity fragments. Exceeding the face budget defers the extra batch instead of silently discarding occluders.
- Unused visual sections have a short, budget-bounded grace period for movement reversals; all data is cleared when observation ends, on world changes or disconnect. It is visual data, not bullet collision geometry or persistent world state. Terrain outside this bounded neighbourhood remains subject to existing vanilla/Voxy rendering limits; this change does not implement a separate Voxy far-depth occlusion fallback.

Under `[occlusion]`, `maxCellsPerPlayer` (32768) bounds each observation update, `maxCellsPerTick` (131072) bounds all players in a server tick, `maxSectionsPerPlayer` (128) bounds the region roster, `maxSectionsPerPacket` (4) bounds changed-section transmissions, `refreshTicks` (10) schedules rescans, and `maxFacesPerFrame` (100000) bounds client depth geometry. Observation-update cadence and budget contention can extend refresh latency. Solid occupancy costs 512 bytes per changed section before protocol overhead/compression; textured plants add state entries. Smaller budgets trade slower first visibility/terrain refresh for less work, not incomplete visible cover.

## Retained Oculus support

Oculus 1.8.0 is optional and client-only, with isolated class linkage when absent. Shadow passes skip depth picking, extra entity/target rendering and far-plane changes. With a shader pack active, smoke uses the shader pipeline's translucent particle material; the separate bright bullet head remains disabled. Without Oculus, the vanilla path remains active.

The current focused shader test uses the supplied NeoVoxy 0.3.3 Forge client, Embeddium 0.3.31, Oculus 1.8.0 and Photon. Complementary Unbound r5.8.1 was also used during the initial port. Select a compatible shader pack in `build/client-forge-shader-test/config/oculus.properties`, then run:

```text
gradlew runClientShaderTest -Penable_voxy_runtime=true -Penable_oculus_runtime=true
```

`runClientLongRangeTest -Penable_voxy_runtime=true` includes cover 36 blocks in front of an 880-block target, moving entities and repeated off-axis aim. Its backstop makes those aimed shots hit a real block, so retention does not rely on air transit.

## Simplified cover and shader lighting isolation

The server no longer queries voxel selection shapes for cover. Thin plant models such as grass and crops retain atlas-alpha masking; volumetric plants such as cocoa and azalea use whole cells. This is visual occlusion only and does not change bullet collision.

The old invisible plant pass wrote directly into persistent world depth using the active entity material. Shader packs could consume that depth in lighting, fog or other post-processing despite there being no corresponding visible surface. The new transient pass uses an isolated alpha-only program with all color outputs disabled. After extra entities finish drawing, it removes unchanged plant depth while preserving nearer entity fragments and restores framebuffer, sampler and render states. Only the projected mask rectangle is copied on the GPU, when cover exists; no GPU-to-CPU depth readback, terrain loading, or network messages are added.

Historical validation before the shared-section refactor: build and 119 unit tests passed; all 73 server GameTests passed after replacing the old shape-opening tests with full-cell checks. The Voxy client test recorded zero dropouts and zero leaks across 460 visible and 444 hidden moving-target frames, plus 986 aim-return frames with zero dropouts. The final Oculus/Photon run passed remote picking, entities, targets and smoke. Its GPU checks verified texture-alpha masking, removal of temporary plant depth, retention of closer entity depth, and unchanged depth/color in the active world framebuffer. This addresses a concrete source of shader lighting interference; it does not reproduce every lighting transition possible in a user's world or shader settings.

## Development client dependencies

Voxy runtime is enabled by default for this workspace (`enable_voxy_runtime=true`); Loom supplies remapped Voxy and Embeddium. Set it to `false` to test without Voxy. Do not place their ordinary release jars in `run/client/mods`: those jars retain production method names and cause `NoSuchMethodError` in the named development environment. Use the Gradle runtime switches instead, then reload the Gradle project after changes. This setting does not make Voxy a required dependency of the published mod. The manually installed jars from the September 27 startup failure were preserved in `run/client/mods-backup-20260927/`.

## Shared-section refactor validation

- Final build: 117 unit tests and all 72 server GameTests passed. The old polygon-clip tests were replaced with greedy section geometry checks; server tests cover resumable atomic publication, confirmed removals, unchanged revisions, missing chunks, material/crop state, and shared-section packet bounds.
- Voxy without shaders: half-wall/full-leaf/log cover and removal passed at 880 blocks with cover 36 blocks in front of the target. There were 460 visible and 459 hidden moving-target frames with zero dropouts/leaks, 459 extreme-low-budget off-axis frames with zero leaks, and 989 aim-return frames with zero dropouts.
- Oculus/Photon: the same complete live-cover suite passed, with 221 visible and 216 hidden moving-target frames (zero dropouts/leaks), 226 extreme-low-budget off-axis frames (zero leaks), and 526 aim-return frames (zero dropouts). Tests also exercise actual save/rejoin, target-side AI, TACZ target recovery and delayed projectile damage after unscoping.
- GPU checks verify that leaves/glass use the same full-cell depth as stone, grass/crops retain alpha, rejected over-budget masks write no depth, mask cleanup preserves closer entity depth, and world depth/material outputs remain unchanged when no entity is drawn. The Photon live-cover test compares retained depth fragments because final display colors are not yet composed during the entity stage; the non-shader test compares actual color pixels.

These are controlled regression fixtures, not a guarantee for every modded entity renderer or shader pack. No full-distance Voxy depth fallback is claimed.

## Moving targets and players

The client recomputes mandatory cover for each entity's interpolated draw position every frame. Server preparation is independent of that readiness decision: unfinished optional sections no longer make a currently covered entity disappear. This also prevents an independently moving remote player from reusing cover that only applies to an older server position.

`prefetchMargin=8` prepares resident sections in all directions. Measured displacement and current velocity can enlarge that margin to `maxPrefetchMargin=24`, using `prefetchTicks=12` as the speed multiplier. Direction is not predicted. `retentionTicks=60` retains recently used section versions and unfinished scan jobs for quick reversals, within `maxSectionsPerPlayer`. Mandatory sections receive admission and initial-publication priority over preparation. Preparation prefers the nearest section ring and already acknowledged data when budgets are tight. Complete retained/prepared occluders continue participating in depth masking so a known wall is not dropped merely because the target crosses a section edge. No extra terrain tickets are introduced.

High speed, abrupt reversals, jumps and teleports are handled by the same actual-position coverage check. If movement outruns the available data or server budgets, only the uncovered entity is temporarily withheld until its actual required mask arrives. Teleportation never authorizes stale cover at the destination and does not scan/load the entire teleport path. These limits cannot guarantee uninterrupted display for arbitrarily fast movement into unprepared terrain.

Movement-fix validation: build and 117 unit tests passed; all 74 server GameTests passed, including 1,440 randomized nearby player displacements/turns, vertical movement, teleport coverage rejection and bounded retention/reversal checks. The real client fixture now moves across section boundary Z=16 over a roughly 12-block span at up to about 1.38 blocks/tick, with repeated reversals. Voxy recorded 458 visible / 460 hidden frames with zero dropouts/leaks; Photon recorded 203 / 213 with zero dropouts/leaks. Both also passed off-axis low-budget retention, return-to-aim, save/rejoin and projectile checks. These movement fixtures use available target-side terrain: movement behind the fixture backstop or into genuinely unavailable coverage must remain hidden and is not counted as a display-stability guarantee. Player movement geometry is tested with a server-side player fixture; this is not a two-client multiplayer stress benchmark.


## Projectile exit from target coverage

Each managed shot now retains an entered-target state. Transit before its first leased target core remains unchanged. After entry, its actual movement may continue through touching or overlapping cores, but ends at the first gap or outer boundary. A fast projectile entering and leaving within one tick is clipped at the same boundary. Native TACZ entity penetration remains active inside that continuous coverage; nearer blocks, exhausted penetration and unavailable target chunks terminate first. Missing target chunks are unknown, not air. No boundary impact decal or sound is fabricated.

This uses the already retained target bounds and resident terrain only: no new tickets, transit snapshots or path-wide chunk loading. Never entering any selected core still leaves the existing distance/lifetime limits in charge. The server sends the actual terminal position to smoke rendering; already emitted smoke continues its normal fade instead of disappearing immediately.

Boundary-fix validation: build and all 117 unit tests passed; the final complete server run passed all 78 GameTests. New coverage includes same-tick entry/exit, touching/overlapping cores, gaps, reverse/vertical exits, state retention across ticks, native multi-entity penetration, nearer-wall priority, unavailable target terrain without chunk loading, and terminal smoke retention/fade. The existing fallen-target test now expects a target-boundary stop after passing the lowered target. One intermediate full run had two intermittent pre-existing scope-selection failures; the unchanged tests passed on the final rerun. No client visual or two-player recording was performed for this change.
