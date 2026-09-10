package tacz.longrangesniping.test;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.scoping.ScopeActivity;
import tacz.longrangesniping.scoping.ScopeSubscriptions;
import tacz.longrangesniping.scoping.ScopeVisibility;
import tacz.longrangesniping.terrain.SectionKey;
import tacz.longrangesniping.terrain.SnapshotStore;
import tacz.longrangesniping.terrain.TerrainSnapshots;

import java.util.UUID;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScopeActivityGameTests {
    @GameTest(template = "ballistics", batch = "scope_activity", timeoutTicks = 850)
    public static void savedMobReloadsSimulatesAndReleasesBoundedRegion(GameTestHelper helper) {
        var level = helper.getLevel();
        var original = ServerConfig.current();
        var activity = ScopeActivity.INSTANCE;
        var cache = TerrainSnapshots.INSTANCE;
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "activity-test"));
        var fixture = new LocalBallisticsGameTests.Fixture(helper, player);
        var second = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "activity-share"));
        new LocalBallisticsGameTests.Fixture(helper, second);
        var secondOperator = IGunOperator.fromLivingEntity(second);
        boolean[] aiming = {false}, sharing = {false}, finished = {false};
        int cx = 6200, cz = 6200, wx = cx * 16, wz = cz * 16;
        Cow[] restored = {null};
        net.minecraft.world.entity.monster.Zombie[] zombie = {null};
        Wolf[] wolf = {null}; Sheep[] prey = {null}; Vec3[] initial = {null};
        var foreign = net.minecraft.server.level.TicketType.<net.minecraft.world.level.ChunkPos>create("activity_test_foreign",
                java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
        var foreignPos = new net.minecraft.world.level.ChunkPos(cx, cz);
        com.tacz.guns.entity.EntityKineticBullet[] shot = {null};
        Cow[] shotTarget = {null};
        var operator = IGunOperator.fromLivingEntity(player);
        operator.aim(false); secondOperator.aim(false);
        // Register only observer/nearest-player lists; no normal player chunk tickets are installed by this fixture.
        register(player, true);
        level.players().add(player); register(second, true); level.players().add(second);
        Runnable cleanup = () -> {
            finished[0] = true; aiming[0] = false; sharing[0] = false; operator.aim(false); secondOperator.aim(false);
            ScopeSubscriptions.INSTANCE.replace(player, java.util.List.of());
            ScopeSubscriptions.INSTANCE.replace(second, java.util.List.of());
            activity.releaseAll();
            level.getChunkSource().removeRegionTicket(foreign, foreignPos, 3, foreignPos, true);
            tacz.longrangesniping.ballistics.PrototypeBallistics.INSTANCE.unload(new net.neoforged.neoforge.event.level.LevelEvent.Unload(level));
            register(player, false); level.players().remove(player);
            register(second, false); level.players().remove(second);
            LocalBallisticsGameTests.setSettings(original);
        };
        java.util.function.Consumer<Runnable> checked = action -> {
            try { action.run(); } catch (Throwable failure) { cleanup.run(); throw failure; }
        };
        var restricted = new ServerConfig.Activity(true, 1, 8, 20, 25, 4096, 4, 2, 100);
        var allowed = new ServerConfig.Activity(true, 1, 8, 20, 196, 4096, 4, 2, 100);
        java.util.function.Consumer<ServerConfig.Activity> configure = config -> LocalBallisticsGameTests.setSettings(
                new ServerConfig.Settings(true, original.policy(), 1024, 32, 128, true, new ServerConfig.Ballistics(true, 1024, 128, 200, true),
                        new ServerConfig.Snapshots(true, 65536, 256, 10, 0, 8192, 8192), original.scopeRendering(), config));
        configure.accept(restricted);
        helper.onEachTick(() -> { if (!finished[0]) {
            // GameTestServer omits normal chunk-queue polling in waitUntilNextTick. Reproduce the ordinary
            // server's idle work/time here; production activity never polls tasks or blocks on loading.
            for (int work = 0; work < 1024 && level.getChunkSource().pollTask(); work++) {}
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            player.setOldPosAndRot(); second.setOldPosAndRot();
            operator.aim(aiming[0]); player.setYRot(-90); player.setYHeadRot(-90); player.setXRot(0);
            secondOperator.aim(sharing[0]); second.setYRot(-90); second.setYHeadRot(-90); second.setXRot(0);
        } });
        var saved = EntityType.COW.create(level);
        checked.accept(() -> {
            player.setPos(wx - 216, 80, wz + 8); player.setYRot(-90); player.setYHeadRot(-90); player.setXRot(0);
            second.setPos(player.position()); second.setYRot(-90); second.setYHeadRot(-90); second.setXRot(0);
            for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) level.getChunk(x, z);
            for (int x = cx - 14; x < cx; x++) level.getChunk(x, cz);
            for (int x = wx - 8; x < wx + 24; x++) for (int z = wz - 8; z < wz + 24; z++)
                level.setBlock(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 79; y <= 84; y++) level.setBlock(new BlockPos(wx + 14, y, wz + 8), Blocks.STONE.defaultBlockState(), 2);
            saved.setPos(wx + 8, 80, wz + 12); level.addFreshEntity(saved);
            var key = new SectionKey(TerrainSnapshots.dimension(level), cx, 5, cz);
            var corridor = new java.util.ArrayList<SectionKey>();
            for (int x = cx - 14; x <= cx; x++) for (int y = 4; y <= 5; y++)
                corridor.add(new SectionKey(TerrainSnapshots.dimension(level), x, y, cz));
            for (int i = 0; i < 4096 && corridor.stream().anyMatch(k -> cache.coverage(k) == SnapshotStore.Coverage.UNKNOWN); i++) cache.tick();
            helper.assertTrue(cache.coverage(key) != SnapshotStore.Coverage.UNKNOWN, "Target snapshot missing");
            helper.assertTrue(new ScopeVisibility(level, 65536).firstBlock(player.getEyePosition(),
                    player.getEyePosition().add(300, 0, 0)).isPresent(), "Known terrain did not locate reticle target");
        });
        helper.runAfterDelay(200, () -> checked.accept(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null && level.getEntity(saved.getUUID()) == null,
                    "Fixture target did not unload before activation");
            helper.assertTrue(new ScopeVisibility(level, 65536).firstBlock(player.getEyePosition(),
                    player.getEyePosition().add(300, 0, 0)).isPresent(), "Unloaded terrain lost reticle target");
            aiming[0] = true; sharing[0] = true; operator.aim(true); secondOperator.aim(true);
        }));
        helper.runAfterDelay(230, () -> checked.accept(() -> {
            helper.assertTrue(activity.status().regions() == 0 && activity.status().budgetDenied() > 0,
                    "FULL footprint budget was bypassed: " + activity.status()
                            + ", prediction=" + activity.predictionStatus(player.getUUID()) + ", decision=" + tacz.longrangesniping.compat.tacz.TaczBridge.evaluate(player)
                            + ", look=" + player.getViewVector(1) + ", target=" + new ScopeVisibility(level, 65536).firstBlock(
                            player.getEyePosition(), player.getEyePosition().add(player.getViewVector(1).scale(1024)))
                            + ", registered=" + (level.getServer().getPlayerList().getPlayer(player.getUUID()) == player));
            helper.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null, "Budget refusal loaded target");
            configure.accept(allowed);
        }));
        helper.runAfterDelay(300, () -> checked.accept(() -> {
            helper.assertTrue(activity.status().ready() == 1 && activity.status().fullChunks() == 49,
                    "Shared target core not ready or wrong footprint: " + activity.status());
            restored[0] = (Cow) level.getEntity(saved.getUUID());
            helper.assertTrue(restored[0] != null && restored[0] != saved && restored[0].tickCount > 0,
                    "Saved entity was not restored/ticked");
            helper.assertTrue(!restored[0].isNoAi() && restored[0].getNoActionTime() < 100, "Remote idle goal gating remains");
            initial[0] = restored[0].position();
            helper.assertTrue(restored[0].getNavigation().moveTo(wx + 17, 80, wz + 12, 1), "Native navigation could not create path");
            wolf[0] = EntityType.WOLF.create(level); wolf[0].setPos(wx + 1, 80, wz + 2); level.addFreshEntity(wolf[0]);
            prey[0] = EntityType.SHEEP.create(level); prey[0].setPos(wx + 6, 80, wz + 2); level.addFreshEntity(prey[0]);
            wolf[0].setTarget(prey[0]);
            zombie[0] = EntityType.ZOMBIE.create(level); zombie[0].setPos(wx + 7, 80, wz + 15);
            zombie[0].setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            level.addFreshEntity(zombie[0]);
            zombie[0].checkDespawn();
            helper.assertTrue(zombie[0].isAlive() && !zombie[0].isPersistenceRequired(),
                    "Distance despawn removed active zombie or permanently changed persistence");
            helper.assertTrue(level.getChunkSource().getChunkNow(cx - 8, cz) == null, "Activity loaded the intervening corridor");
        }));
        helper.runAfterDelay(390, () -> checked.accept(() -> {
            helper.assertTrue(restored[0].position().distanceTo(initial[0]) > 0.3, "Native remote navigation stayed frozen");
            helper.assertTrue(!prey[0].isAlive() || prey[0].getHealth() < prey[0].getMaxHealth(), "Remote wolf never attacked its prey");
            helper.assertTrue(zombie[0].isAlive() && zombie[0].getNoActionTime() < 100,
                    "Active hostile despawned or retained idle gating");
            // Fire a deliberately slow, short-native-life bullet beyond 128 blocks, then immediately unscope.
            // Remove the AI fixtures from this firing lane; they were already checked above.
            restored[0].discard(); wolf[0].discard(); prey[0].discard(); zombie[0].discard();
            level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new net.minecraft.world.phys.AABB(wx-16, 79, wz-16, wx+32, 85, wz+32)).forEach(net.minecraft.world.entity.Entity::discard);
            shotTarget[0] = EntityType.COW.create(level); shotTarget[0].setPos(wx + 8, 80.32, wz + 8);
            shotTarget[0].setNoAi(true); shotTarget[0].setNoGravity(true);
            shotTarget[0].getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
            shotTarget[0].setHealth(1000); level.addFreshEntity(shotTarget[0]);
            Vec3 shooterPosition = player.position(), origin = player.getEyePosition();
            shot[0] = fixture.bullet(new Vec3(4, 5, 4), new Vec3(2, 0, 0));
            player.setPos(shooterPosition); shot[0].setPos(origin);
            LocalBallisticsGameTests.setField(shot[0], "startPos", origin);
            LocalBallisticsGameTests.setField(shot[0], "life", 2);
            LocalBallisticsGameTests.setField(shot[0], "gravity", 0f);
            LocalBallisticsGameTests.setField(shot[0], "friction", 0f);
            LocalBallisticsGameTests.setField(shot[0], "pierce", 1);
            helper.assertTrue(!level.addFreshEntity(shot[0]), "Qualified shot did not enter default snapshot scheduler");
            helper.assertTrue(activity.status().flightPins() == 1, "Shot did not retain its existing target region");
            aiming[0] = false; operator.aim(false);
        }));
        helper.runAfterDelay(420, () -> checked.accept(() -> {
            helper.assertTrue(activity.status().regions() == 1 && activity.status().fullChunks() == 49 && activity.active(shotTarget[0]),
                    "First observer release removed the second observer's shared region");
            sharing[0] = false; secondOperator.aim(false);
        }));
        helper.runAfterDelay(430, () -> checked.accept(() -> {
            level.getChunkSource().addRegionTicket(foreign, foreignPos, 3, foreignPos, true);
        }));
        helper.runAfterDelay(450, () -> checked.accept(() -> {
            helper.assertTrue(activity.status().regions() == 1 && activity.status().flightPins() == 1
                    && !shot[0].isRemoved() && shotTarget[0].getHealth() == 1000,
                    "Both scopes closed: region released before the slow bullet arrived");
            level.getChunkSource().removeRegionTicket(foreign, foreignPos, 3, foreignPos, true);
        }));
        helper.runAfterDelay(470, () -> checked.accept(() -> {
            helper.assertTrue(level.isPositionEntityTicking(shotTarget[0].blockPosition()) && activity.status().flightPins() == 1,
                    "Foreign ticket removal interfered with our in-flight hold");
            helper.assertTrue(level.getChunkSource().getChunkNow(cx - 8, cz) == null, "Flight loaded the corridor");
            level.getChunkSource().addRegionTicket(foreign, foreignPos, 3, foreignPos, true);
        }));
        helper.runAfterDelay(520, () -> checked.accept(() -> {
            helper.assertTrue(shot[0].isRemoved() && shotTarget[0].getHealth() < 1000,
                    "Snapshot bullet did not hit beyond 128 blocks after both scopes closed");
            helper.assertTrue(activity.status().regions() == 0 && activity.status().flightPins() == 0,
                    "Completed flight retained its region");
            helper.assertTrue(level.getEntity(shotTarget[0].getUUID()) == shotTarget[0]
                    && level.isPositionEntityTicking(shotTarget[0].blockPosition()),
                    "Our ticket removal unloaded the foreign loader's existing target");
            aiming[0] = true; sharing[0] = true; operator.aim(true); secondOperator.aim(true);
        }));
        helper.runAfterDelay(555, () -> checked.accept(() -> {
            helper.assertTrue(activity.status().regions() == 1 && level.getEntity(shotTarget[0].getUUID()) == shotTarget[0],
                    "Scoping an externally loaded area duplicated/replaced its entity");
            level.getChunkSource().removeRegionTicket(foreign, foreignPos, 3, foreignPos, true);
        }));
        helper.runAfterDelay(565, () -> checked.accept(() -> {
            helper.assertTrue(level.isPositionEntityTicking(shotTarget[0].blockPosition()), "External unload removed our renewed region");
            aiming[0] = false; sharing[0] = false; operator.aim(false); secondOperator.aim(false);
        }));
        helper.runAfterDelay(600, () -> checked.accept(() -> {
            helper.assertTrue(activity.status().regions() == 0, "Non-shooting scope release leaked region");
        }));
        helper.runAfterDelay(760, () -> checked.accept(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null, "Released region stayed loaded");
            cleanup.run(); helper.succeed();
        }));
    }
    @SuppressWarnings("unchecked")
    private static void register(net.minecraft.server.level.ServerPlayer player, boolean add) {
        try {
            var list = player.server.getPlayerList();
            var playersField = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            var idsField = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
            playersField.setAccessible(true); idsField.setAccessible(true);
            var players = (java.util.List<net.minecraft.server.level.ServerPlayer>) playersField.get(list);
            var ids = (java.util.Map<UUID, net.minecraft.server.level.ServerPlayer>) idsField.get(list);
            if (add) {
                var channel = net.minecraft.network.Connection.class.getDeclaredField("channel");
                channel.setAccessible(true);
                channel.set(player.connection.getConnection(), new io.netty.channel.embedded.EmbeddedChannel());
                players.add(player); ids.put(player.getUUID(), player);
            }
            else { players.remove(player); ids.remove(player.getUUID()); player.connection.getConnection().channel().close(); }
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

}
