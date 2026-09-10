package tacz.longrangesniping.test;

import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.compiler.LuaC;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.SegmentTrace;
import tacz.longrangesniping.compat.tacz.BallisticPreview;
import tacz.longrangesniping.mixin.KineticBulletAccessor;
import tacz.longrangesniping.scoping.ImpactPrediction;
import tacz.longrangesniping.terrain.CollisionCell;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ImpactPredictionGameTests {
    private static long deadline() { return System.nanoTime() + 60_000_000_000L; }
    private static ImpactPrediction.Result predict(BallisticPreview shot, SegmentTrace.Source source, List<AABB> entities) {
        return ImpactPrediction.predict(shot, source, entities, 1024, 600, 65536, 8192, deadline());
    }

    @GameTest(template = "ballistics", batch = "prediction")
    public static void bundledSnipersMatchNativeAndFollowCache(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        player.setPos(helper.absoluteVec(new Vec3(4, 5, 4)));
        player.setYRot(-90); player.setXRot(-8); player.setOnGround(false);
        player.setDeltaMovement(new Vec3(.13, -.07, .21));
        var operator = IGunOperator.fromLivingEntity(player);
        for (String id : List.of("ai_awp", "m95", "m107", "m700", "springfield1873", "kar98")) {
            var stack = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:" + id)).setAmmoCount(7)
                    .build(helper.getLevel().registryAccess());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var gun = IGun.getIGunOrNull(stack);
            var index = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow();
            var data = index.getGunData();
            var cache = new AttachmentCacheProperty(); cache.eval(stack, data); operator.updateCacheProperty(cache);
            var preview = BallisticPreview.read(player).orElseThrow(() -> new AssertionError("Unsupported bundled gun: " + id));
            var bullet = new EntityKineticBullet(helper.getLevel(), player, stack, data.getAmmoId(), gun.getGunId(stack),
                    false, data, index.getBulletData());
            var fields = (KineticBulletAccessor) bullet;
            float speed = (float) (cache.<Float>getCache(GunProperties.AMMO_SPEED) * AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER.get()) / 20f;
            ((com.tacz.guns.item.ModernKineticGunItem) gun).doBulletSpread(operator.getDataHolder(), stack, player, bullet,
                    1, speed, 0, player.getXRot(), player.getYRot());
            helper.assertTrue(preview.gravity() == fields.taczlrs$getGravity() && preview.friction() == fields.taczlrs$getFriction()
                    && preview.lifeTicks() == fields.taczlrs$getLife(), "Native parameters differ: " + id);
            helper.assertTrue(preview.start().distanceTo(bullet.position()) < 1e-8
                    && preview.velocity().distanceTo(bullet.getDeltaMovement()) < 1e-4, "Native launch differs: " + id + " preview=" + preview + " native=" + bullet.position() + " / " + bullet.getDeltaMovement());
            cache.setCache(GunProperties.AMMO_SPEED, cache.<Float>getCache(GunProperties.AMMO_SPEED) * .5f);
            var modified = BallisticPreview.read(player).orElseThrow();
            helper.assertTrue(modified.velocity().subtract(player.getKnownMovement()).distanceTo(
                    preview.velocity().subtract(player.getKnownMovement()).scale(id.equals("springfield1873") ? .5 : 1)) < 1e-4,
                    "Preview ignored updated attachment property: " + id);
            helper.assertTrue(gun.getCurrentAmmoCount(stack) == 7, "Preview consumed ammunition");
        }
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "prediction")
    public static void holdoverHitsForegroundAndHonorsUnknown(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var actual = BallisticPreview.read(fixture.player).orElseThrow();
        var shot = new BallisticPreview(new Vec3(.5, 5, .5), new Vec3(actual.velocity().length(), .05, 0),
                actual.gravity(), actual.friction(), actual.lifeTicks());
        SegmentTrace.Source landscape = (x, y, z) -> y < 0 || x >= 900 ? CollisionCell.FULL : CollisionCell.AIR;
        var hit = predict(shot, landscape, List.of());
        helper.assertTrue(hit.end() == ImpactPrediction.End.BLOCK && hit.position().x < 900 && hit.position().y < .01,
                "Holdover selected background mountain instead of ground: " + hit);
        var noDrop = predict(new BallisticPreview(shot.start(), shot.velocity(), 0, 0, 600), landscape, List.of());
        helper.assertTrue(noDrop.end() == ImpactPrediction.End.BLOCK && noDrop.position().x >= 900,
                "Control ray did not hit background mountain: " + noDrop);
        var unknown = predict(shot, (x, y, z) -> x >= 64 && x < 80 ? null : landscape.cell(x, y, z), List.of());
        helper.assertTrue(unknown.end() == ImpactPrediction.End.UNKNOWN && unknown.target() == null,
                "Unknown corridor admitted a target: " + unknown);
        var wall = predict(shot, (x, y, z) -> x == 64 ? CollisionCell.FULL : landscape.cell(x, y, z), List.of());
        helper.assertTrue(wall.end() == ImpactPrediction.End.BLOCK && wall.position().x < 65,
                "Curve skipped foreground wall: " + wall);
        var entity = new AABB(30, 4, -1, 32, 8, 2);
        helper.assertTrue(predict(shot, landscape, List.of(entity)).end() == ImpactPrediction.End.ENTITY,
                "Entity on curved path was ignored");
        helper.assertTrue(predict(shot, (x,y,z) -> x >= 25 ? null : CollisionCell.AIR, List.of(entity)).target() == null,
                "Entity behind unknown terrain admitted a target");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "prediction")
    public static void budgetsRangeLifetimeAndWater(GameTestHelper helper) {
        var shot = new BallisticPreview(new Vec3(0, 10, 0), new Vec3(10, 0, 0), .15f, .015f, 18);
        SegmentTrace.Source air = (x,y,z) -> CollisionCell.AIR;
        var limited = ImpactPrediction.predict(shot, air, List.of(), 25, 600, 65536, 8192, deadline());
        helper.assertTrue(limited.end() == ImpactPrediction.End.RANGE && limited.target() == null, "Range bypassed");
        var time = ImpactPrediction.predict(shot, air, List.of(), 1024, 600, 65536, 8192, System.nanoTime() - 1);
        var cells = ImpactPrediction.predict(shot, air, List.of(), 1024, 600, 2, 8192, deadline());
        var entities = ImpactPrediction.predict(shot, air, List.of(new AABB(999,0,0,1000,1,1)), 1024, 600, 65536, 0, deadline());
        helper.assertTrue(time.end() == ImpactPrediction.End.BUDGET && cells.end() == ImpactPrediction.End.BUDGET
                && entities.end() == ImpactPrediction.End.BUDGET, "Resource caps bypassed");
        var shortLife = ImpactPrediction.predict(shot, air, List.of(), 1024, 2, 65536, 8192, deadline());
        helper.assertTrue(shortLife.end() == ImpactPrediction.End.LIFETIME && shortLife.target() == null, "Lifetime bypassed");
        var water = ImpactPrediction.predict(shot, (x,y,z) -> CollisionCell.WATER, List.of(), 1024, 2, 65536, 8192, deadline());
        Vec3 expected = shot.start().add(shot.velocity()).add(shot.velocity().scale((double)(1f - .4f))
                .add(0, (double) -(shot.gravity() * .6f), 0));
        helper.assertTrue(water.position().distanceTo(expected) < 1e-10, "Water integration differs from snapshot flight");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "prediction")
    public static void scriptInspectionNeverExecutesCallbacks(GameTestHelper helper) throws Exception {
        for (String body : List.of("api:shootOnce(api:isShootingNeedConsumeAmmo())",
                "api.projectileSpeedMultiplier = 2; api:shootOnce(true)", "error('must not execute')")) {
            String script = "return function(api) " + body + " end";
            var p = LuaC.instance.compile(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), "test").p[0];
            boolean allowed = BallisticPreview.standardShoot(new LuaClosure(p, LuaValue.NIL));
            helper.assertTrue(allowed == body.startsWith("api:shootOnce(api:"), "Unsafe firing callback accepted");
        }
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "prediction", timeoutTicks = 200)
    public static void warmedPredictionCost(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var nativeShot = BallisticPreview.read(fixture.player).orElseThrow();
        var shot = new BallisticPreview(new Vec3(.5, 5, .5), new Vec3(nativeShot.velocity().length(), 1, 0),
                nativeShot.gravity(), nativeShot.friction(), nativeShot.lifeTicks());
        SegmentTrace.Source landscape = (x,y,z) -> y < 0 ? CollisionCell.FULL : CollisionCell.AIR;
        for (int i = 0; i < 20; i++) predict(shot, landscape, List.of());
        long[] times = new long[80];
        ImpactPrediction.Result result = null;
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime(); result = predict(shot, landscape, List.of()); times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        TaczLongRangeSniping.LOGGER.info("Prediction benchmark (synthetic terrain, actual AWP data): median={}ms p95={}ms result={}",
                times[40] / 1e6, times[76] / 1e6, result);
        helper.assertTrue(result != null && (result.end() == ImpactPrediction.End.BLOCK || result.end() == ImpactPrediction.End.RANGE),
                "Benchmark did not finish the bounded curved path");
        helper.succeed();
    }
}
