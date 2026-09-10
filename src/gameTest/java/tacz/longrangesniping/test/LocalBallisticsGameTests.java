package tacz.longrangesniping.test;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.LocalBallistics;
import tacz.longrangesniping.ballistics.PrototypeBallistics;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tacz.longrangesniping.compat.tacz.TaczBridge;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.mixin.KineticBulletAccessor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LocalBallisticsGameTests {
    private static final ServerConfig.Ballistics LIMITS = new ServerConfig.Ballistics(true, 256, 16, 600);

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void extendedLifetimeStillObeysDistanceAndDuration(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var scheduler = new LocalBallistics(helper.getLevel());
        var bullet = fixture.bullet(new Vec3(4, 5, 4), new Vec3(1, 0, 0));
        setField(bullet, "life", 2); setField(bullet, "gravity", 0f); setField(bullet, "friction", 0f);
        accept(helper, scheduler, bullet, new ServerConfig.Ballistics(true, 16, 1, 40, true));
        for (int i = 0; i < 40 && !bullet.isRemoved(); i++) scheduler.tick();
        helper.assertTrue(bullet.tickCount == 16 && scheduler.status().rangeLimited() == 1,
                "Extended bullet retained short native lifetime or bypassed range: " + scheduler.status());
        var stopped = fixture.bullet(new Vec3(4, 5, 4), Vec3.ZERO);
        setField(stopped, "life", 2); setField(stopped, "gravity", 0f); setField(stopped, "friction", 0f);
        accept(helper, scheduler, stopped, new ServerConfig.Ballistics(true, 16, 1, 8, true));
        for (int i = 0; i < 10 && !stopped.isRemoved(); i++) scheduler.tick();
        helper.assertTrue(stopped.isRemoved() && scheduler.status().active() == 0 && scheduler.status().lifetimeLimited() == 1,
                "Stationary extended bullet bypassed duration cap");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void airTrajectoryLifetimeAndReleaseMatchNative(GameTestHelper helper) {
        compareTrajectory(helper, false);
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void waterDragAndGravityMatchNative(GameTestHelper helper) {
        for (int x = 3; x <= 12; x++) for (int y = 3; y <= 6; y++) for (int z = 3; z <= 6; z++) {
            helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
        }
        compareTrajectory(helper, true);
    }

    private static void compareTrajectory(GameTestHelper helper, boolean water) {
        var fixture = new Fixture(helper);
        var nativeBullet = fixture.bullet(new Vec3(4, 5, 4), new Vec3(0.4, 0.1, 0));
        var managed = fixture.bullet(new Vec3(4, 5, 4), new Vec3(0.4, 0.1, 0));
        setField(nativeBullet, "life", 8);
        setField(managed, "life", 8);
        var scheduler = new LocalBallistics(helper.getLevel());
        accept(helper, scheduler, managed, LIMITS);
        // Accepted state is immutable: release and a different held item must not stop flight.
        IGunOperator.fromLivingEntity(fixture.player).aim(false);
        fixture.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (int i = 0; i < 8 && !nativeBullet.isRemoved(); i++) {
            helper.getLevel().tickNonPassenger(nativeBullet);
            scheduler.tick();
            helper.assertTrue(nativeBullet.position().distanceTo(managed.position()) < 1.0e-9, "Position differs on tick " + i);
            helper.assertTrue(nativeBullet.getDeltaMovement().distanceTo(managed.getDeltaMovement()) < 1.0e-9, "Velocity differs on tick " + i);
            helper.assertTrue(nativeBullet.tickCount == managed.tickCount && nativeBullet.isRemoved() == managed.isRemoved(), "Lifetime differs");
        }
        helper.assertTrue(nativeBullet.isRemoved() && scheduler.status().active() == 0, "Lifetime did not retire the bullet");
        helper.assertTrue(!water || managed.isInWater(), "Water fixture was not exercised");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void damageHeadshotsAndPiercingMatchNative(GameTestHelper helper) {
        compareDamage(helper, LIMITS);
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void snapshotDamageHeadshotsAndPiercingMatchNative(GameTestHelper helper) {
        // Range clipping selects the snapshot-safe path even though these targets are loaded.
        compareDamage(helper, new ServerConfig.Ballistics(true, 16, 16, 600));
    }

    private static void compareDamage(GameTestHelper helper, ServerConfig.Ballistics limits) {
        var fixture = new Fixture(helper);
        List<Zombie> targets = List.of(target(helper, 10), target(helper, 14), target(helper, 18));
        // Same three targets and deterministic projectile seed, varying only who schedules the tick.
        for (boolean head : List.of(false, true)) {
            double y = 4 + (head ? targets.getFirst().getEyeHeight() : 0.7);
            var reference = fixture.bullet(new Vec3(4, y, 4), new Vec3(18, 0, 0));
            var managed = fixture.bullet(new Vec3(4, y, 4), new Vec3(18, 0, 0));
            setField(reference, "pierce", 2);
            setField(managed, "pierce", 2);
            List<Boolean> nativeHeads = new ArrayList<>();
            List<Boolean> managedHeads = new ArrayList<>();
            Consumer<EntityHurtByGunEvent> hits = event -> {
                if (!(event instanceof EntityHurtByGunEvent.Pre)) return;
                if (event.getBullet() == reference) nativeHeads.add(event.isHeadShot());
                if (event.getBullet() == managed) managedHeads.add(event.isHeadShot());
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityHurtByGunEvent.class, hits);
            try {
                resetTargets(targets);
                helper.getLevel().tickNonPassenger(reference);
                List<Float> referenceHealth = targets.stream().map(Zombie::getHealth).toList();
                resetTargets(targets);
                var scheduler = new LocalBallistics(helper.getLevel());
                accept(helper, scheduler, managed, limits);
                int ammoBefore = IGun.getIGunOrNull(fixture.stack).getCurrentAmmoCount(fixture.stack);
                scheduler.tick();
                scheduler.tick();
                helper.assertTrue(referenceHealth.equals(targets.stream().map(Zombie::getHealth).toList()), "Native and managed damage differ");
                helper.assertTrue(nativeHeads.equals(managedHeads) && managedHeads.equals(List.of(head, head)), "Headshot/pierce events differ: " + managedHeads);
                helper.assertTrue(targets.get(0).getHealth() < 1000 && targets.get(1).getHealth() < 1000
                        && targets.get(2).getHealth() == 1000, "Pierce count/order was not preserved");
                helper.assertTrue(IGun.getIGunOrNull(fixture.stack).getCurrentAmmoCount(fixture.stack) == ammoBefore, "Scheduler consumed ammo a second time");
                helper.assertTrue(scheduler.status().active() == 0 && managed.isRemoved(), "Spent bullet remains active");
            } finally {
                NeoForge.EVENT_BUS.unregister(hits);
            }
        }
        targets.forEach(Zombie::discard);
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void wallBetweenEndpointsPreventsDamage(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var target = target(helper, 14);
        helper.setBlock(new BlockPos(10, 4, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(10, 5, 4), Blocks.STONE);
        var bullet = fixture.bullet(new Vec3(4, 4.7, 4), new Vec3(18, 0, 0));
        var scheduler = new LocalBallistics(helper.getLevel());
        accept(helper, scheduler, bullet, LIMITS);
        scheduler.tick();
        helper.assertTrue(bullet.isRemoved() && target.getHealth() == 1000, "Bullet tunneled through the wall");
        helper.assertTrue(scheduler.status().unknown() == 0, "Test stopped on unloaded terrain rather than the wall");
        target.discard();
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void unloadedMiddleIsRejectedWithoutLoading(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        // Isolated coordinates: only the two endpoint chunks are intentionally loaded by this fixture.
        var level = helper.getLevel();
        int x = 700000;
        int z = 700000;
        level.getChunk(x, z);
        level.getChunk(x + 4, z);
        helper.assertTrue(level.getChunkSource().getChunkNow(x + 2, z) == null, "Middle was already loaded");
        var bullet = fixture.bullet(new Vec3(4, 5, 4), new Vec3(64, 0, 0));
        bullet.setPos(x * 16 + 8, 80, z * 16 + 8);
        var scheduler = new LocalBallistics(level);
        accept(helper, scheduler, bullet, LIMITS);
        var start = bullet.position();
        scheduler.tick();
        helper.assertTrue(bullet.isRemoved() && bullet.getX() > start.x && bullet.getX() < (x + 1) * 16,
                "Bullet did not stop inside the known prefix");
        helper.assertTrue(level.getChunkSource().getChunkNow(x + 2, z) == null, "Scheduler loaded the missing middle chunk");
        helper.assertTrue(scheduler.status().unknown() == 1, "Unknown stop was not recorded");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void rangeCapacityAndUnsupportedAmmunition(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var scheduler = new LocalBallistics(helper.getLevel());
        var limits = new ServerConfig.Ballistics(true, 16, 1, 600);
        var first = fixture.bullet(new Vec3(4, 5, 4), new Vec3(17, 0, 0));
        accept(helper, scheduler, first, limits);
        var extra = fixture.bullet(new Vec3(4, 5, 4), new Vec3(1, 0, 0));
        helper.assertTrue(scheduler.admit(extra, TaczBridge.inspectNewBullet(extra).orElseThrow(), limits) == LocalBallistics.Admission.CAPACITY,
                "Capacity did not retain native ownership of excess shots");
        helper.assertTrue(!extra.isRemoved(), "Rejected shot was consumed");
        scheduler.tick();
        helper.assertTrue(first.isRemoved() && first.tickCount == 1 && scheduler.status().rangeLimited() == 1
                && Math.abs(first.position().distanceTo(helper.absoluteVec(new Vec3(4, 5, 4))) - 16) < 1.0e-7, "Range limit failed");
        for (String flag : List.of("explosion", "igniteEntity", "igniteBlock")) {
            var special = fixture.bullet(new Vec3(4, 5, 4), new Vec3(1, 0, 0));
            setField(special, flag, true);
            helper.assertTrue(scheduler.admit(special, TaczBridge.inspectNewBullet(special).orElseThrow(), LIMITS)
                    == LocalBallistics.Admission.UNSUPPORTED && !special.isRemoved(), "Unsupported ammo was taken over: " + flag);
        }
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void canceledDamageEventsRemainEffective(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var target = target(helper, 10);
        var bullet = fixture.bullet(new Vec3(4, 4.7, 4), new Vec3(12, 0, 0));
        Consumer<EntityHurtByGunEvent> cancel = event -> { if (event.getBullet() == bullet) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, EntityHurtByGunEvent.class, cancel);
        try {
            var scheduler = new LocalBallistics(helper.getLevel());
            accept(helper, scheduler, bullet, LIMITS);
            scheduler.tick();
            helper.assertTrue(target.getHealth() == 1000, "TACZ damage cancellation was bypassed");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
            target.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void registeredEntityCannotBeTickedTwiceAndClearReleasesShots(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var scheduler = new LocalBallistics(helper.getLevel());
        var bullet = fixture.bullet(new Vec3(4, 5, 4), new Vec3(1, 0, 0));
        int originalLife = ((KineticBulletAccessor) bullet).taczlrs$getLife();
        accept(helper, scheduler, bullet, new ServerConfig.Ballistics(true, 128, 128, 600, true));
        helper.assertTrue(((KineticBulletAccessor) bullet).taczlrs$getLife() > originalLife, "Lifetime extension was not installed");
        // The manually admitted object becomes world-owned; release aim so the global event subscriber does not re-admit this fixture.
        IGunOperator.fromLivingEntity(fixture.player).aim(false);
        helper.getLevel().addFreshEntity(bullet);
        scheduler.tick();
        helper.assertTrue(bullet.tickCount == 0 && !bullet.isRemoved() && scheduler.status().active() == 0,
                "World-owned entity was advanced or discarded by scheduler");
        helper.assertTrue(((KineticBulletAccessor) bullet).taczlrs$getLife() == originalLife, "Native ownership did not restore original lifetime");
        bullet.discard();
        IGunOperator.fromLivingEntity(fixture.player).aim(true);
        var pending = fixture.bullet(new Vec3(4, 5, 4), new Vec3(1, 0, 0));
        accept(helper, scheduler, pending, LIMITS);
        scheduler.clear();
        helper.assertTrue(pending.isRemoved() && scheduler.status().active() == 0, "Cleanup retained a projectile");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "local_ballistics")
    public static void spawnEventTransfersOwnershipAndLevelTickAdvancesOnce(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var original = ServerConfig.current();
        try {
            // Test-only immutable config replacement: exercise the actual global event subscribers.
            setSettings(new ServerConfig.Settings(original.available(), original.policy(), original.maxDistance(),
                    original.maxTrackedEntities(), original.snapshotMemoryMiB(), original.diagnostics(), LIMITS, original.snapshots()));
            var bullet = fixture.bullet(new Vec3(4, 5, 4), new Vec3(1, 0, 0));
            helper.assertTrue(!helper.getLevel().addFreshEntity(bullet), "Native spawn was not canceled");
            helper.assertTrue(helper.getLevel().getEntity(bullet.getUUID()) == null && !bullet.isRemoved(),
                    "Taken-over bullet was registered or prematurely discarded");
            helper.assertTrue(PrototypeBallistics.INSTANCE.status(helper.getLevel()).active() == 1, "No scheduler ownership");
            // Disabling new takeovers and releasing aim must leave this shot's ownership intact.
            setSettings(original);
            IGunOperator.fromLivingEntity(fixture.player).aim(false);
            PrototypeBallistics.INSTANCE.tick(new LevelTickEvent.Post(() -> true, helper.getLevel()));
            helper.assertTrue(bullet.tickCount == 1, "Shot did not advance exactly once");
            PrototypeBallistics.INSTANCE.tick(new LevelTickEvent.Post(() -> true, helper.getLevel()));
            helper.assertTrue(bullet.tickCount == 2, "Second tick duplicated or lost advancement");
        } finally {
            setSettings(original);
            PrototypeBallistics.INSTANCE.unload(new LevelEvent.Unload(helper.getLevel()));
        }
        helper.succeed();
    }

    static void setSettings(ServerConfig.Settings settings) {
        try {
            Field field = ServerConfig.class.getDeclaredField("current");
            field.setAccessible(true);
            field.set(null, settings);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    static void accept(GameTestHelper helper, LocalBallistics scheduler, EntityKineticBullet bullet, ServerConfig.Ballistics limits) {
        helper.assertTrue(scheduler.admit(bullet, TaczBridge.inspectNewBullet(bullet).orElseThrow(), limits)
                == LocalBallistics.Admission.ACCEPTED, "Admission failed");
    }

    private static Zombie target(GameTestHelper helper, int x) {
        var mob = helper.spawn(EntityType.ZOMBIE, new Vec3(x, 4, 4));
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        mob.getAttribute(Attributes.ARMOR).setBaseValue(0);
        mob.setHealth(1000);
        return mob;
    }

    private static void resetTargets(List<Zombie> targets) {
        targets.forEach(target -> { target.setHealth(1000); target.invulnerableTime = 0; target.setDeltaMovement(Vec3.ZERO); });
    }

    static void setField(EntityKineticBullet bullet, String name, Object value) {
        try {
            Field field = EntityKineticBullet.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bullet, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    static final class Fixture {
        final GameTestHelper helper;
        final Player player;
        final ItemStack stack;
        Fixture(GameTestHelper helper) {
            this(helper, helper.makeMockPlayer(GameType.SURVIVAL));
        }
        Fixture(GameTestHelper helper, Player player) {
            this.helper = helper;
            this.player = player;
            stack = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:ai_awp"))
                    .setAmmoCount(7).putAttachment(AttachmentType.SCOPE, ResourceLocation.parse("tacz:scope_mk5hd"))
                    .build(helper.getLevel().registryAccess());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var gun = IGun.getIGunOrNull(stack);
            var tag = gun.getAttachmentTag(stack, AttachmentType.SCOPE).copy();
            tag.putInt("ZoomNumber", 1);
            gun.setAttachmentTag(stack, AttachmentType.SCOPE, tag);
            var operator = IGunOperator.fromLivingEntity(player);
            operator.initialData();
            var data = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
            var cache = new AttachmentCacheProperty();
            cache.eval(stack, data);
            operator.updateCacheProperty(cache);
            operator.aim(true);
        }
        EntityKineticBullet bullet(Vec3 relative, Vec3 velocity) {
            player.setPos(helper.absoluteVec(relative));
            var gun = IGun.getIGunOrNull(stack);
            var data = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
            var bullet = new EntityKineticBullet(helper.getLevel(), player, stack, data.getAmmoId(), gun.getGunId(stack),
                    false, data, data.getBulletData());
            bullet.setPos(helper.absoluteVec(relative));
            bullet.setDeltaMovement(velocity);
            bullet.getRandom().setSeed(12345);
            return bullet;
        }
    }
}
