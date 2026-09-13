# TACZ: Art of Sniping

**English** | [简体中文](README.zh-CN.md)

**TACZ: AoS** is a TACZ addon for long-range sniping with realistic ballistics. By **Sange**.

Allows players to snipe other entities from extreme distances.

## Requirements

Minecraft **1.21.1**, **NeoForge 21.1.248 or newer**, Java **21**, and the [TACZ 1.21.1 port](https://modrinth.com/mod/tacz-1.21.1) (**1.1.8-hotfix-r6**, supported range: below 1.2). Install AoS and TACZ on both the client and server.

## What's included

- **Rangefinder:** a two-block-tall tripod that enables scoped sniping within **3 blocks**. It enables the features; measurements follow the player's aim.
- **Tactical Collection Belt:** wear it in the leggings slot or an existing Curios belt slot. Kills with **any TACZ gun** bring dropped items and XP to your feet, including other mods' items using the normal death-drop system. No rangefinder or aiming is needed.
- Distant entity rendering and simulation, scope range/wind/humidity readings, and visible bullet smoke trails.
- Multiplayer flyby sounds, delayed distant gunshots, and special long-range player kill messages.
- Real-scale gravity, optional wind and humidity effects, and configurable gun speed/drag overrides. These physics changes also affect ordinary TACZ kinetic rounds outside sniping mode.
- Zero random spread and reduced scope idle sway while prone with an allowed gun. Recoil remains.

Both items appear in TACZ's miscellaneous creative tab alongside its gun workbench. Craft them as follows; `.` means an empty slot:

| Rangefinder | Tactical Collection Belt |
| --- | --- |
| `G D G` | `. D .` |
| `. I .` | `L R L` |
| `I . I` | `. A .` |

`G` = clear glass pane, `D` = diamond, `I` = iron ingot, `L` = leather, `R` = redstone dust, `A` = gold ingot.

## How to play

1. Explore the area you intend to shoot into. Use [NHblock-Johnsnow's NeoVoxy fork](https://github.com/NHblock-Johnsnow/neo-voxy) to see terrain beyond vanilla render distance.
2. Place a rangefinder, stay within **3 blocks**, and equip an allowed gun with an allowed scope.
3. Aim steadily at visible terrain near your target. AoS loads and simulates the target area so distant creatures, players and TACZ targets can appear and be hit.
4. Read the scope information, then compensate for bullet drop and wind. Target areas remain loaded briefly while adjusting your aim; accepted shots keep their target area available during flight.

All aiming magnifications can enable sniping. Readouts appear only in supported optical scope views, not backup iron sights or open sights. Defaults include **AWP, M95, M107, M700, Kar98 and Springfield 1873**, with several TACZ scopes.

Without Voxy, terrain outside vanilla render distance cannot be selected as a new target. A sightline crossing ungenerated terrain will not load a distant target.

## Performance and aiming trade-offs

AoS uses a small set of target areas instead of loading every chunk along a bullet's entire flight path.

**Why select an area with the crosshair before following the bullet's trajectory?** The crosshair follows the camera's line of sight, so AoS can quickly obtain a visible target surface from Voxy's already-rendered LOD depth without loading the intervening chunks. A bullet's curved path does not follow that same viewing ray and may pass behind terrain hidden from the camera. The current LOD depth integration cannot reliably determine its first collision from that view alone. Accurate trajectory-first targeting would require collision data along the path up to the first hit—such as terrain snapshots read or cached from the relevant chunks—and additional collision checks. Maintaining and retrieving that data adds work and can delay targeting at extreme distances. Crosshair-first loading avoids this requirement:

1. **The crosshair requests loading.** While you are eligible to snipe and aiming steadily, AoS requests the area around the visible surface under your crosshair. Beyond vanilla render distance, Voxy's rendered LOD surface supplies that candidate position. This reflects what is visible in the LOD image, not a precise check of the latest terrain along the entire path.
2. **Target areas remain briefly after you look away**—about three seconds by default—so you have time to adjust your aim.
3. **The predicted trajectory keeps a target area active.** AoS periodically recalculates the curved trajectory using your gun's ballistics and environmental effects. If it predicts a block or entity hit within an existing target area, that area is renewed. The curve does not load new areas or keep every loaded chunk it passes through active.

This matters when compensating for bullet drop: raising the crosshair above a creature may point it at a much more distant hillside. The original target area can remain active because the predicted bullet still lands there, even while the crosshair requests the farther area. **Aim too high, however, and the predicted shot may miss the original area entirely.** Once its retention time expires, the creature may disappear from your view. This can be normal: point the crosshair back at its last position or nearby terrain, wait for it to appear, then raise your aim a little less.

**The crosshair loads; the predicted impact retains.** Loading, entity AI and synchronization still have a cost, especially with several snipers, but requests, active areas and prediction work are budgeted. The accuracy trade-off is deliberate: sniping bullets check collisions **inside retained target areas only**, so obstacles elsewhere along the flight path can be bypassed.

For example, the crosshair may see a target while the lower, curved trajectory would actually hit an intervening ridge. If that ridge is outside the retained target areas, the shot can pass through it. AoS accepts this visibility-versus-trajectory edge case in exchange for responsive, lower-cost long-range aiming. Its impact on balance depends on the terrain and how players use it; terrain inside the target areas still blocks shots normally.

## Configuration

Launch the game once to generate `config/tacz_aos-server.toml`. A world's `serverconfig/tacz_aos-server.toml`, if present, takes precedence. In multiplayer, edit the **server's** active file. Restart the world/server after changes.

To support another TACZ gun pack, append its **gun ID** to `guns` and its **scope attachment ID** to `scopeRules` under `[eligibility]`. Use pack IDs such as `pack:rifle`, not the generic TACZ gun item ID. Keep the existing entries you want to use. For example:

```toml
[eligibility]
guns = ["tacz:ai_awp", "your_pack:sniper"]
scopeRules = ["tacz:scope_mk5hd|5,25,1.25", "your_pack:scope"]

[limits]
maxDistance = 1024
```

Replace `your_pack:*` with real installed IDs. A scope may optionally specify its actual magnifications in gun-pack order, e.g. `your_pack:scope|4,8,1`; this is projection metadata, **not a magnification whitelist**. The client uses the pack's actual scope/view data. Custom scope renderers may need additional compatibility work for readouts.

`limits.maxDistance` is the maximum targeting distance in blocks: **1024 by default**, or **2048** for longer shots. It also limits sniping bullet travel by default, so curved flight consumes slightly more distance than a straight line. `ballistics.maxDistance = 0` follows this limit; a positive value can only lower the bullet limit. Raising the range does not remove terrain or loading-budget restrictions.

## Compatibility

| Optional mod | Integration |
| --- | --- |
| [**NeoVoxy (NHblock-Johnsnow)**](https://github.com/NHblock-Johnsnow/neo-voxy) | Select distant LOD terrain; AoS supplies target-area entities. Compatibility has only been tested with this fork. Other Voxy forks are not guaranteed to work, so this fork is recommended for playing with AoS. |
| **Iris** | Scope targeting, entity occlusion and bullet trails with shaders. Supports 1.8.x; appearance depends on the shader pack. |
| **Curios** | Collection belt support in an existing functional `belt` slot. No extra slots are added; cosmetic slots do not activate it. Supports 9.5.1–9.x. |
| **ParCool / Crawl on Demand** | Their crawling states enable prone accuracy and idle-sway suppression for allowed guns. |

Use builds for Minecraft 1.21.1 NeoForge and install each optional mod's own dependencies. Other TACZ gun packs using standard gun/projectile APIs can be added through configuration; custom projectiles or renderers may require integration. Existing chunk-loader and player chunk tickets are respected.

**Not supported together: [Tacz Bullet Improvement](https://modrinth.com/mod/tacz-tacz-bullet-improvement).** Its published description replaces TACZ's traveling bullets with instant raycast hits, which conflicts with AoS's flight-time simulation, bullet drop, wind drift and impact prediction. Its listed releases also target Minecraft 1.20–1.20.1 Forge, rather than AoS's 1.21.1 NeoForge. Do not combine them; a future port would still need dedicated integration.

## License

[MIT](LICENSE) — Copyright © 2026 **Sange**. Third-party assets retain their own rights; see [asset notices](src/main/resources/THIRD_PARTY_NOTICES.md) and the [original template license](TEMPLATE_LICENSE.txt).
