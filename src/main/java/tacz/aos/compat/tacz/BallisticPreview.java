package tacz.aos.compat.tacz;

import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.item.ModernKineticGunItem;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.compiler.LuaC;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Read-only mean shot, excluding random spread/recoil and future target movement. Never fires a dummy bullet. */
public record BallisticPreview(Vec3 start, Vec3 velocity, float gravity, float friction, int lifeTicks,
                               tacz.aos.wind.WindVector crosswind) {
    public BallisticPreview(Vec3 start, Vec3 velocity, float gravity, float friction, int lifeTicks) {
        this(start, velocity, gravity, friction, lifeTicks, tacz.aos.wind.WindVector.ZERO);
    }
    private static final Prototype DELEGATING_SHOOT = delegatingShoot();

    public static Optional<BallisticPreview> read(Player player) {
        var stack = player.getMainHandItem();
        var gun = IGun.getIGunOrNull(stack);
        // Other implementations and arbitrary Lua callbacks can alter launch state at firing time.
        if (gun == null || gun.getClass() != ModernKineticGunItem.class
                || !(player instanceof IGunOperator operator)) return Optional.empty();
        var index = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElse(null);
        var cache = operator.getCacheProperty();
        if (index == null || cache == null || operator.getDataHolder() == null) return Optional.empty();
        var script = index.getScript();
        if (script != null && (script.getmetatable() != null || !script.rawget("modify_property").isnil()
                || !script.rawget("calcSpread").isnil()
                || !standardShoot(script.rawget("shoot")))) return Optional.empty();
        var data = index.getBulletData();
        if (data == null) return Optional.empty();
        com.tacz.guns.resource.pojo.data.gun.Ignite ignite = cache.getCache(com.tacz.guns.resource.modifier.custom.IgniteModifier.ID);
        com.tacz.guns.resource.pojo.data.gun.ExplosionData explosion = cache.getCache(com.tacz.guns.resource.modifier.custom.ExplosionModifier.ID);
        if (data.getIgnite().isIgniteEntity() || data.getIgnite().isIgniteBlock()
                || ignite != null && (ignite.isIgniteEntity() || ignite.isIgniteBlock())
                || explosion != null && explosion.isExplode()) return Optional.empty();
        Float cachedSpeed = cache.getCache(GunProperties.AMMO_SPEED);
        if (cachedSpeed == null) return Optional.empty();
        float speed = (float) (cachedSpeed * AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER.get());
        speed = Mth.clamp(speed / 20.0f, 0, Float.MAX_VALUE);
        float gravity = Mth.clamp(data.getGravity(), 0, Float.MAX_VALUE);
        float friction = Mth.clamp(data.getFriction(), 0, Float.MAX_VALUE);
        var profile = tacz.aos.config.ServerConfig.gunOverrides().get(gun.getGunId(stack).toString());
        if (profile != null) {
            speed = profile.speedOr(speed);
            gravity = profile.gravityOr(gravity);
            friction = profile.frictionOr(friction);
        }
        float seconds = data.getLifeSecond();
        if (!Float.isFinite(speed) || !Float.isFinite(gravity) || !Float.isFinite(friction)
                || !Float.isFinite(seconds)) return Optional.empty();
        int life = Mth.clamp((int) (seconds * 20f), 1, Integer.MAX_VALUE);
        // TACZ's rich constructor replaces its initial muzzle offset with the interpolated shooter eye position.
        Vec3 start = new Vec3(player.xOld + (player.getX() - player.xOld) / 2,
                player.yOld + (player.getY() - player.yOld) / 2 + player.getEyeHeight(),
                player.zOld + (player.getZ() - player.zOld) / 2);
        // Supported scripts delegate to TACZ's default vanilla launch; custom calcSpread is rejected above.
        Vec3 motion = player.getKnownMovement();
        float pitch = player.getXRot() * ((float) Math.PI / 180f);
        float yaw = player.getYRot() * ((float) Math.PI / 180f);
        Vec3 direction = new Vec3(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
        Vec3 velocity = direction.normalize().scale(speed)
                .add(motion.x, player.onGround() ? 0 : motion.y, motion.z);
        var wind = player.level() instanceof net.minecraft.server.level.ServerLevel server
                ? tacz.aos.wind.WindField.current(server).perpendicular(direction.x, direction.z)
                : tacz.aos.wind.WindVector.ZERO;
        return Optional.of(new BallisticPreview(start, velocity, gravity, friction, life, wind));
    }

    /** Accept plain native delegation (including the bundled Kar98); inspect bytecode without running Lua. */
    public static boolean standardShoot(LuaValue function) {
        if (function.isnil()) return true;
        if (!(function instanceof LuaClosure closure) || DELEGATING_SHOOT == null) return false;
        var p = closure.p;
        var reference = DELEGATING_SHOOT;
        if (p.numparams != reference.numparams || p.is_vararg != reference.is_vararg
                || p.p.length != 0 || p.upvalues.length != 0 || !Arrays.equals(p.code, reference.code)
                || p.k.length != reference.k.length) return false;
        for (int i = 0; i < p.k.length; i++) if (!p.k[i].raweq(reference.k[i])) return false;
        return true;
    }

    private static Prototype delegatingShoot() {
        String source = "return function(api) api:shootOnce(api:isShootingNeedConsumeAmmo()) end";
        try {
            return LuaC.instance.compile(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                    "tacz_aos_preview_contract").p[0];
        } catch (Exception exception) { return null; }
    }
}
