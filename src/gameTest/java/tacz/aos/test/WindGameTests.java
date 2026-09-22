package tacz.aos.test;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.compat.tacz.BallisticPreview;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.RangePayload;
import tacz.aos.scoping.ImpactPrediction;
import tacz.aos.terrain.CollisionCell;
import tacz.aos.wind.*;

import java.util.List;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WindGameTests {
    @GameTest(setupTicks = 20, template = "ballistics", batch = "wind")
    public static void predictionObeysDragLagFormulaAndWaterExclusion(GameTestHelper helper) {
        float friction = .02f;
        var wind = new WindVector(0, 5);
        var shot = new BallisticPreview(new Vec3(.5, 80, .5), new Vec3(40, 0, 0), .024516625f, friction, 100, wind);
        var result = ImpactPrediction.predict(shot, (x,y,z) -> CollisionCell.AIR, List.of(),
                4096, 20, 65536, 8192, System.nanoTime() + 5_000_000_000L);
        double q = (double) (1f - friction);
        double expectedDrift = (wind.z() * friction / 20) / (1-q) * (20 - (1-Math.pow(q,20))/(1-q));
        helper.assertTrue(result.end() == ImpactPrediction.End.LIFETIME && Math.abs(result.position().z - .5 - expectedDrift) < 1e-8,
                "Prediction wind drift disagrees with closed-form relative-air drag: " + result);
        var clear = new BallisticPreview(shot.start(), shot.velocity(), shot.gravity(), friction, 100);
        var noWind = ImpactPrediction.predict(clear, (x,y,z) -> CollisionCell.AIR, List.of(),
                4096, 20, 65536, 8192, System.nanoTime() + 5_000_000_000L);
        helper.assertTrue(result.position().x == noWind.position().x && result.position().y == noWind.position().y,
                "Crosswind changed forward speed or vertical drop");
        helper.assertTrue(WindPhysics.afterDrag(shot.velocity(), wind, 0, false).equals(shot.velocity()), "Zero drag must cause zero wind drift");
        helper.assertTrue(WindPhysics.afterDrag(shot.velocity(), wind, .4f, true).equals(shot.velocity()), "Air wind affected underwater bullet");
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "wind")
    public static void disabledWindAndPerShotStateAreIndependentOfAim(GameTestHelper helper) throws Exception {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        player.setPos(player.position().add(0, 20, 0)); player.setOldPosAndRot();
        IGunOperator.fromLivingEntity(player).aim(false);
        var old = ServerConfig.wind();
        var config = ServerConfig.class.getDeclaredField("wind"); config.setAccessible(true);
        config.set(null, WindSettings.DEFAULT);
        try {
            var gun = IGun.getIGunOrNull(fixture.stack);
            var data = com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(fixture.stack)).orElseThrow().getGunData();
            var bullet = new EntityKineticBullet(helper.getLevel(), player, fixture.stack, data.getAmmoId(), gun.getGunId(fixture.stack),
                    false, data, data.getBulletData());
            var worldWind = WindField.current(helper.getLevel());
            bullet.shoot(-worldWind.z(), 0, worldWind.x(), 40, 0);
            var frozen = ((WindBulletAccess) bullet).tacz_aos$wind();
            helper.assertTrue(frozen.speed() > 0 && Math.abs(frozen.speed() - worldWind.speed()) < 1e-8,
                    "Unscoped shot away from instrument did not capture global wind");
            config.set(null, WindSettings.DISABLED);
            helper.assertTrue(WindField.current(helper.getLevel()).equals(WindVector.ZERO), "Disable did not clear current wind");
            helper.assertTrue(((WindBulletAccess) bullet).tacz_aos$wind().equals(frozen), "Existing shot's wind changed mid-flight");
            bullet.shoot(1, 0, 0, 40, 0);
            helper.assertTrue(((WindBulletAccess) bullet).tacz_aos$wind().speed() == 0, "New launch retained wind after disabling");
        } finally { config.set(null, old); }
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "wind")
    public static void windReadoutNetworkRoundTripAndBounds(GameTestHelper helper) {
        var sample = new RangePayload(helper.getLevel().dimension().location(), Vec3.ZERO, new Vec3(1,0,0), 100,
                true, 3, -4, true, 65.3f);
        var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            RangePayload.STREAM_CODEC.encode(buffer, sample);
            helper.assertTrue(sample.equals(RangePayload.STREAM_CODEC.decode(buffer)), "Wind readout lost data in transit");
        } finally { buffer.release(); }
        boolean rejected = false;
        try { new RangePayload(sample.dimension(), Vec3.ZERO, sample.direction(), 1, true, Float.NaN, 0, false, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Non-finite wind accepted by network payload");
        for (float humidity : new float[] {Float.NaN, -1, 101}) {
            rejected = false;
            try { new RangePayload(sample.dimension(), Vec3.ZERO, sample.direction(), 1, false, 0, 0, true, humidity); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Invalid humidity accepted by network payload");
        }
        helper.succeed();
    }
}
