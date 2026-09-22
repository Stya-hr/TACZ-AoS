package tacz.aos.test;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.compat.tacz.*;
import tacz.aos.config.ServerConfig;
import tacz.aos.humidity.*;
import tacz.aos.mixin.KineticBulletAccessor;
import tacz.aos.wind.WindSettings;
import java.util.List;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HumidityGameTests {
    public static HumiditySettings constant(double rh) {
        var d = HumiditySettings.DEFAULT;
        var weather = new HumiditySettings.Weather(0, 0);
        return new HumiditySettings(true, false, 0, 4000, 0, 5, 99, 50, d.densityCoefficient(),
                d.distribution(), List.of(new HumiditySettings.Phase(0, 24000, rh)), weather, weather, weather);
    }
    @GameTest(setupTicks = 20, template = "ballistics", batch = "humidity")
    public static void actualAndPredictedTrajectoriesUseSameMoistureAndFixedGravity(GameTestHelper helper) throws Exception {
        var oldH = ServerConfig.humidity(); var oldW = ServerConfig.wind();
        var h = ServerConfig.class.getDeclaredField("humidity"); h.setAccessible(true);
        var w = ServerConfig.class.getDeclaredField("wind"); w.setAccessible(true);
        try {
            h.set(null, constant(95)); w.set(null, WindSettings.DISABLED);
            var fixture = new LocalBallisticsGameTests.Fixture(helper);
            var player = fixture.player; var level = helper.getLevel();
            player.setPos(player.getX(), 180, player.getZ()); player.setOldPosAndRot();
            player.setOnGround(true); player.setDeltaMovement(Vec3.ZERO); player.setYRot(-90); player.setXRot(0);
            IGunOperator.fromLivingEntity(player).aim(false);
            var preview = BallisticPreview.read(player).orElseThrow();
            var gun = IGun.getIGunOrNull(fixture.stack);
            var data = TimelessAPI.getCommonGunIndex(gun.getGunId(fixture.stack)).orElseThrow().getGunData();
            float raw = ServerConfig.gunOverrides().get("tacz:ai_awp").frictionOr(0);
            helper.assertTrue(Math.abs(preview.friction() - .023740457f) < 1e-8, "Wrong real-scale moisture correction");
            Vec3 expected = preview.start(), motion = preview.velocity();
            for (int i = 0; i < 8; i++) {
                expected = expected.add(motion);
                motion = motion.scale(1f - preview.friction()).add(0, -preview.gravity(), 0);
            }
            for (int cx = (int) player.getX() >> 4; cx <= ((int) expected.x >> 4) + 1; cx++)
                for (int cz = ((int) player.getZ() >> 4) - 1; cz <= ((int) player.getZ() >> 4) + 1; cz++) level.getChunk(cx, cz);
            for (int mode = 0; mode < 2; mode++) {
                var bullet = new EntityKineticBullet(level, player, fixture.stack, data.getAmmoId(), gun.getGunId(fixture.stack), false, data, data.getBulletData());
                if (mode == 0) bullet.shoot(1, 0, 0, 20, 0);
                else bullet.shootFromRotation(player, 0, -90, 0, 20, new org.joml.Vector2d());
                var fields = (KineticBulletAccessor) bullet;
                helper.assertTrue(fields.tacz_aos$getFriction() == preview.friction() && fields.tacz_aos$getGravity() == GunPhysics.GRAVITY_PER_TICK,
                        "Launch route diverged from prediction");
                // Another launch call cannot multiply the humidity correction a second time.
                GunPhysics.launchSpeed(bullet, 20);
                helper.assertTrue(fields.tacz_aos$getFriction() == preview.friction(), "Compounded humidity on re-launch");
                for (int i = 0; i < 8; i++) {
                    if (mode == 0) level.tickNonPassenger(bullet);
                    else {
                        bullet.setOldPosAndRot(); bullet.tickCount++;
                        tacz.aos.ballistics.TargetBallistics.tick(level, bullet, 4096,
                                new tacz.aos.ballistics.TargetCollisionSource(level, List.of()));
                    }
                }
                helper.assertTrue(bullet.position().distanceTo(expected) < 1e-4 && bullet.getDeltaMovement().distanceTo(motion) < 1e-4,
                        "Native/managed flight differs from humidity preview, mode=" + mode);
                h.set(null, HumiditySettings.DISABLED);
                helper.assertTrue(fields.tacz_aos$getFriction() == preview.friction(), "Existing bullet changed when humidity disabled");
                var dry = new EntityKineticBullet(level, player, fixture.stack, data.getAmmoId(), gun.getGunId(fixture.stack), false, data, data.getBulletData());
                dry.shoot(1, 0, 0, 20, 0);
                helper.assertTrue(((KineticBulletAccessor) dry).tacz_aos$getFriction() == raw
                        && ((KineticBulletAccessor) dry).tacz_aos$getGravity() == GunPhysics.GRAVITY_PER_TICK,
                        "Disabled humidity changed base drag or disabled gravity");
                dry.discard(); bullet.discard(); h.set(null, constant(95));
            }
            TaczArtOfSniping.LOGGER.info("HUMIDITY_PHYSICS PASSED: native/script launch, prediction, native/managed flight, disable, fixed gravity");
            helper.succeed();
        } finally { h.set(null, oldH); w.set(null, oldW); HumidityField.clear(); }
    }
}
