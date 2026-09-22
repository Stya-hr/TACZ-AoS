# Tactical collection belt: additional death drops

The belt still requires a fatal TACZ damage source and an equipped belt. It does not require a rangefinder or a sniper rifle. Standard `LivingDropsEvent` items and normal death experience retain their existing handling.

The following NeoForge 1.21.1 releases have additional item spawning paths:

| Mod / audited version | Actual producer | Handling |
| --- | --- | --- |
| [Lightman's Currency](https://modrinth.com/mod/lightmans-currency/version/n2ynBeOK), 1.21-2.3.0.5 | `LootManager.onEntityDeath` → `DropEntityLoot` → `InventoryUtil.dumpContents` | Collect the actual spawned rewards after death processing. |
| Lightman's Currency, same version | `EventHandler.playerDrops`, including its manual wallet spawning option | Recognize direct wallet item spawning during the wrapped death; normal event-list drops already use the standard collector. Keep the mod's own wallet rules. |
| [Collectors Album](https://modrinth.com/mod/collectors-album/version/Yvm5QCJY), 2.8.0 | Injected `collectorsAlbum$die` handler creates an `ItemEntity` directly | Recognize the merged handler, including Mixin's generated name prefix. |
| [Champions-Unofficial](https://modrinth.com/mod/champions-unofficial/version/OQnVOM7w), 21.1.1.7 | `ChampionLootHandler.spawnDrop`, shared by its loot-table and configured rewards | Collect the existing item entity. The experience multiplier uses `LivingExperienceDropEvent` and already works with the normal XP collector. |

## Attribution and cost

`BeltSpecialLootMixin` wraps the synchronous `LivingEntity.die` call. A thread-local scope records the exact victim and fatal source, and is restored in `finally`, including canceled, nested and exceptional deaths. Nothing is stored in a save or retained across ticks.

`EntityJoinLevelEvent` only records newly spawned items during an eligible death. A short-circuit stack walk checks the audited producer methods above; this avoids injecting into another mod's Mixin class and avoids inferring ownership from item IDs, position, or time. No stack walk runs for normal chunk loading or for deaths without an eligible collector. Identical items spawned by unrelated listeners are not collected.

Items are moved only after world insertion and successful completion of the death. This preserves their identity, components, stack counts, pickup delay and other item metadata, while clearing their launch velocity. Canceled entity spawns and removed items are not recreated. No loot is rerolled and no XP is synthesized.

These are optional integrations. Their compile-only dependencies do not become required runtime dependencies or bundled jars. Future releases that rename the audited producer methods will need review; asynchronous rewards and unrelated custom reward systems are outside this integration's scope.

## Verification

Enable the three actual mods and their required libraries only for development testing:

```text
gradlew runGameTestServer -Penable_loot_compat_runtime=true -Pgametest_namespaces=tacz_aos_lootcompat
```

The tests exercise the real Lightman's Currency death listener (with its supported loot modifier producing a deterministic reward), the real Collectors Album death injection (with a deterministic drop provider), and the real Champions spawn helper used by both reward sources. They cover native and managed TACZ shots, unequipped and melee cases, unchanged item data/counts, unrelated items, canceled deaths and scope cleanup. They do not assert the mods' random loot probabilities or separately exercise every wallet rule.

Ordinary tests without these mods use the default `runGameTestServer` configuration.

## Touhou Little Maid P points

Audited release: [1.5.3-neoforge+mc1.21.1](https://modrinth.com/mod/touhou-little-maid/version/tXG1TkGx).

Fairies implement `IHasPowerPoint`. Their `tickDeath` calls `dropPowerPoint`, which creates `EntityPowerPoint` at death tick 20 if `doMobLoot` permits it. These entities are neither items nor vanilla experience orbs, and are generated after the synchronous death scope has ended.

The successful fatal TACZ hit records the eligible belt wearer on the dying entity. An optional Mixin changes only the spawn position and initial velocity of the original P-point entities before insertion into the world. Adult/baby amounts, the original splitting into point entities, death-animation delay, pickup behavior and power-cap handling remain owned by Touhou Little Maid.

The destination is the shooter's current position when points appear. Taking off the belt after the eligible kill does not lose the reward. If the shooter is dead, removed/disconnected or in another dimension, points remain at the fairy. Non-gun deaths and kills without an equipped belt retain normal behavior. Canceled P-point spawns and `doMobLoot=false` are respected.

The recipient reference exists only on the dying entity; there is no global search, timer queue, or saved NBT. If the dying entity is saved/unloaded and reconstructed before its points appear, the transient ownership is not restored and points use the original location.

```text
gradlew runGameTestServer -Penable_maid_compat_runtime=true -Pgametest_namespaces=tacz_aos_lootcompat
```

Three maid-specific tests use the real fairy/P-point classes and death animation code: native and managed TACZ kills of adult/baby fairies, no points before tick 20, unchanged total P value, moved/unequipped shooter, ineligible deaths, removed shooter, mob-loot rules and canceled entity spawns. Without the maid mod, these tests skip their optional class references. Both runtime flags can be enabled to test all four integrations together.
