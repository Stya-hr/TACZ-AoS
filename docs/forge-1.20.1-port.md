# Forge 1.20.1 port

This branch targets Minecraft **1.20.1**, **Forge 47.3.0**, and **Java 17**. The release artifact is `tacz_aos-forge-1.20.1-1.0.0-beta.1.jar`.

## Dependencies and defaults

- Required: official [Timeless and Classics Zero](https://modrinth.com/mod/timeless-and-classics-zero) **1.1.8-hotfix**.
- Optional: the supplied **NeoVoxy 0.3.3 Forge 1.20.1 client** jar, with **stable Embeddium 0.3.31**. The `0.3.31-beta.53` build does not satisfy that Voxy jar's dependency range.
- Optional integrations retain Forge versions of Curios, Lightman's Currency, Collectors Album, Champions Unofficial, Touhou Little Maid, ParCool, and Crawl on Demand. See [dependency details](../libs/README.md).
- Original NeoForge integration code is excluded from this build. Reference copies live in `archive/neoforge-1.21.1-rendering/`; selected shader integration has been ported to the optional Oculus 1.8.0 Forge API.

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

## Validation

The following passed on the Forge development runtime:

- **118 unit tests** and **72 baseline server GameTests**.
- **9 optional-integration GameTests** with the actual Forge dependency jars: special loot, maid power points, Curios, and both optional crawling mods.
- A real client without Voxy: scope HUD/layouts, distant entities and AI, smoke rendering and expiry, rangefinder models, and observer feedback.
- A real client with the supplied Voxy and stable Embeddium: **2-chunk vanilla view distance**, target approximately **880 blocks away**, actual exploration/save/rejoin, Voxy depth picking, distant entity tracking and AI, both TACZ targets and their recovery animations, occlusion, and damage after unscoping.
- A supplemental real-client test of ParCool and Crawl on Demand: prone idle sway suppression and restoration on exit.

The release jar was inspected for Java 17 class versions, Forge metadata, remapped mixin references, 1.20.1 resource paths, and absence of active NeoForge classes. Only MixinExtras is bundled; optional mods and test classes are not bundled. The release jar has not separately been tested in a normal launcher installation.

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

## Remote occlusion correction

Leaves use opaque whole-cell cover. Known geometric block families (slabs, stairs, doors, trapdoors, fences, walls, etc.) retain their simple shapes; other complex or unknown models use conservative whole-cell cover, including protruding geometry. Glass remains transparent.

Grass, crops, and similar vegetation now send bounded block states and positions instead of being excluded from occlusion. The client renders their baked models into depth only, using the current resource pack's texture alpha and the plant's growth state. Texture holes remain visible; this does not install terrain chunks or draw replacement terrain colors. This additional channel is capped at 64 plants per target and 256 per player update, deduplicated for rendering. Existing local scan and solid-geometry budgets remain in effect; the entire flight corridor is not scanned.

Occlusion has been rolled back to the polygon-clipping implementation from `e363173`, before the recent flicker investigation. Solid cover clips extra entity polygons; vegetation still uses texture-alpha depth masks. The later solid depth pass, pending/complete visibility gate, movement-envelope caches, and chunk-section revision tracking have been removed. The original bounded live scan reads resident server blocks and does not load the flight corridor or use Voxy geometry for cover.

The `occlusion.maxPlaneTestsPerFrame` clipping budget is restored. As in that earlier implementation, incomplete or budget-limited observations do not hide an entire entity while waiting for cover data. This rollback can restore the earlier incomplete-occlusion limitations; it is not a claim that the reported flicker has been reproduced or eliminated.

Update **both server and clients** together (`forge-4` network protocol), because the pending/complete field has been removed from occlusion packets. Region loading, ballistic retention, grace periods, and admission throttling retain their later fixes, as described below.

## Scope region lifetime

Reticle acquisition still requires generated-path validation, aim stability, request throttling, and per-player/global budgets. Those admission limits no longer block renewal of an already-owned region. A valid reticle report inside an owned core renews its existing lease even during fast aim movement, without starting new tickets or metadata reads.

Ballistic prediction renews all owned cores intersected by its integrated path, including air segments with no final impact. Previously it renewed only the final collision's region, which allowed holdover or off-axis aim to unload the observed animal after the grace period. Traversal stops at the first actual collision or configured flight/range limit; it never admits additional regions. Unscoping still expires the existing configurable grace (60 ticks by default), and accepted in-flight shots retain independent bounded leases. Shared tickets remain reference-counted.

## Oculus integration

Oculus 1.8.0 is optional and client-only. The nested bridge isolates all Oculus/Iris classes from installations without Oculus. Shadow passes skip terrain-depth capture, aim reports, remote entity/target draws, and the extended far plane. Visible terrain is still picked from the supplied Voxy pipeline's own framebuffer and projection, before hand rendering/post-processing; native terrain picking is unchanged.

With a shader pack active, smoke uses Oculus's translucent particle program and a small periodic alpha texture instead of the custom vanilla smoke shader. This provides the shader pack's render targets, lighting and blending. The separate bright bullet-head discs remain disabled under shaders. Without a pack (or Oculus), normal smoke rendering remains active. Occlusion stays on the restored polygon-clipping implementation; this integration does not reintroduce the reverted cache or solid depth pass.

To run the focused shader scenario, put a NeoVoxy-compatible shader pack in `build/client-forge-shader-test/shaderpacks/` and select it in that client's `config/oculus.properties`, then run:

```text
gradlew runClientShaderTest -Penable_voxy_runtime=true -Penable_oculus_runtime=true
```

This scenario requires an active shader pack and checks 880-block targeting with a two-chunk view, save/rejoin, entity AI, both TACZ targets and their hit/recovery animation, smoke fragments, and damage after unscoping. The independent occlusion stress scenario is not part of this focused shader test.

For an existing development checkout, a previously cached TACZ remap generated without Oculus can retain an unmapped inherited `FullyBufferedMultiBufferSource` method. If a development run reports `NoSuchMethodError: ...m_109911_`, remove only the remapped TACZ jar under the project's `.gradle/loom-cache/remapped_mods/` and rerun with Oculus available. Recreating that mapping resolves the development-only failure; no TACZ runtime patch is bundled.

Validated with Oculus 1.8.0, Embeddium 0.3.31, the supplied NeoVoxy 0.3.3 Forge client jar, and Complementary Unbound r5.8.1: the focused shader scenario passed at 880 blocks with a two-chunk native view, including real save/rejoin and firing after unscoping. GPU queries recorded 936 entity, 198 target, and 417 smoke samples. Scope HUD and smoke screenshots were inspected. Baseline build, 119 unit tests and 77 server GameTests passed without Oculus. The regular client scenario also passed with neither Oculus nor Voxy installed, including scope HUD, native AI, smoke/expiry and observer feedback. These results cover this combination, not every shader pack or Voxy fork.
