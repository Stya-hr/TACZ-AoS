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

## Rollback to the initial Forge port

Entity subscriptions, native pairing/repair, pose interpolation, target-block subscriptions, visible-target reporting, ballistic prediction, region admission and lifetime are restored to `e8e414e` (the initial Forge 1.20.1 port). This removes the later entity-ticking/core readiness gate, combined pose/occlusion manifest, movement-envelope caches and chunk-section revision mixin. Entity observation no longer waits for a completed occlusion scan. The initial port already retains regions on predicted block/entity impacts, not air-only transit.

The retained occlusion fixes use real resident server blocks in the selected core plus a 5x5 neighborhood around the entity, with a 96-block corridor limit. All non-plant block geometry now uses whole cells, including leaves, glass, slabs, stairs, doors and complex models. Thin vegetation uses texture alpha. Air, fluids, fire and invisible utility cells are excluded. Only relevant geometry is sent under the original read/packet budgets. No new terrain tickets or whole-path scans are added. Entity and occlusion updates use separate packets again. Budget pressure does not revoke entity observation or hide all unchecked model polygons; incomplete cover may therefore remain visible.

The network protocol is now `forge-9`; update clients and server together (occlusion refreshes distinguish complete and incomplete scans). Pre-rollback files are saved locally in `build/forge-port/pre-baseline-rollback.zip` and are not bundled in the release jar.

The live geometry query includes a fixed two-block horizontal and one-block vertical margin for movement between pose packets. This expands only the cover query; it does not cache movement envelopes or gate entity visibility.

### Rollback validation

- Build, 119 unit tests and 74 server GameTests passed.
- The Voxy long-range client test passed with native view distance 2 and an approximately 880-block target. Moving-cover checks recorded 410 visible frames and 420 hidden frames, with zero dropouts and zero leaks. The aim-return checks recorded 844 visible frames with zero dropouts.
- The Oculus client test passed with Complementary Unbound r5.8.1, covering shader depth picking, distant entities and AI, target hits and smoke. This focused shader test does not run the entire moving-occlusion stress test.
- The jar includes the optional shader bridge and excludes the removed chunk-section revision hook and test classes. These controlled tests do not establish the cause of flickering in other gameplay scenes.

## Retained Oculus support

Oculus 1.8.0 is optional and client-only, with isolated class linkage when absent. Shadow passes skip depth picking, extra entity/target rendering and far-plane changes. With a shader pack active, smoke uses the shader pipeline's translucent particle material; the separate bright bullet head remains disabled. Without Oculus, the vanilla path remains active.

The focused shader test uses the supplied NeoVoxy 0.3.3 Forge client, Embeddium 0.3.31 and Complementary Unbound r5.8.1. Select a compatible shader pack in `build/client-forge-shader-test/config/oculus.properties`, then run:

```text
gradlew runClientShaderTest -Penable_voxy_runtime=true -Penable_oculus_runtime=true
```

`runClientLongRangeTest -Penable_voxy_runtime=true` includes cover 36 blocks in front of an 880-block target, moving entities and repeated off-axis aim. Its backstop makes those aimed shots hit a real block, so retention does not rely on air transit.

## Simplified cover and shader lighting isolation

The server no longer queries voxel selection shapes for cover. Thin plant models such as grass and crops retain atlas-alpha masking; volumetric plants such as cocoa and azalea use whole cells. This is visual occlusion only and does not change bullet collision.

The old invisible plant pass wrote directly into persistent world depth using the active entity material. Shader packs could consume that depth in lighting, fog or other post-processing despite there being no corresponding visible surface. The new transient pass uses an isolated alpha-only program with all color outputs disabled. After extra entities finish drawing, it removes unchanged plant depth while preserving nearer entity fragments and restores framebuffer, sampler and render states. Only the projected plant rectangle is copied on the GPU, only when textured cover exists; no GPU-to-CPU depth readback, terrain loading, or network messages are added.

Validation of the simplified-cover change: build and 119 unit tests passed; all 73 server GameTests passed after replacing the old shape-opening tests with full-cell checks. The Voxy client test recorded zero dropouts and zero leaks across 460 visible and 444 hidden moving-target frames, plus 986 aim-return frames with zero dropouts. The final Oculus/Photon run passed remote picking, entities, targets and smoke. Its GPU checks verified texture-alpha masking, removal of temporary plant depth, retention of closer entity depth, and unchanged depth/color in the active world framebuffer. This addresses a concrete source of shader lighting interference; it does not reproduce every lighting transition possible in a user's world or shader settings.

## Development client dependencies

Voxy runtime is enabled by default for this workspace (`enable_voxy_runtime=true`); Loom supplies remapped Voxy and Embeddium. Set it to `false` to test without Voxy. Do not place their ordinary release jars in `run/client/mods`: those jars retain production method names and cause `NoSuchMethodError` in the named development environment. Use the Gradle runtime switches instead, then reload the Gradle project after changes. This setting does not make Voxy a required dependency of the published mod. The manually installed jars from the September 27 startup failure were preserved in `run/client/mods-backup-20260927/`.

## Off-axis occlusion refresh

Occlusion refresh order is now a stable entity-ID order with a rotating starting point, independent of crosshair priority. Packets carry the whole current observation roster and an explicit completeness flag. Budget-limited scans, truncated geometry and missing resident chunks cannot masquerade as a confirmed clear view. The client retains previous cover for incomplete scans; a complete empty scan removes it, and unsubscription, dimension change and entity UUID changes clear the corresponding history. This is bounded visual history, not a terrain snapshot service or an entity readiness gate. Retained cover can be stale until the required terrain can be checked again; previously unknown obstacles still cannot be invented without real block data.

Client polygon work is divided into independent shares among entities with box cover, rather than being consumed by the first entities drawn. The overall configured work cap remains bounded; extremely small budgets can still leave new/unchecked geometry visible. Full-cell block rules and Oculus plant-depth isolation are unchanged.

Validation: 119 unit tests and 75 server GameTests passed. The Voxy client regression deliberately reduces the per-player cover scan budget to one cell while looking sideways at an approximately 880-block target: 457 off-axis frames had zero leaks. Moving-target checks recorded 459 visible and 458 hidden frames with zero dropouts/leaks; 990 aim-return frames had zero dropouts. Restoring the scan budget and removing the wall restored visibility. These are controlled regression scenarios, not a guarantee of complete cover under every resource limit or unavailable terrain condition.
