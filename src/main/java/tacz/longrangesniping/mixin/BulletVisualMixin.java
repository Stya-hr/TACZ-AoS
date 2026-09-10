package tacz.longrangesniping.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.TacHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tacz.longrangesniping.ballistics.BulletVisualAccess;

@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class BulletVisualMixin implements BulletVisualAccess {
    @Unique private boolean taczlrs$capture;
    @Unique private Vec3 taczlrs$impact;
    @Override public void taczlrs$beginVisualStep() { taczlrs$capture = true; taczlrs$impact = null; }
    @Override public Vec3 taczlrs$endVisualStep() { taczlrs$capture = false; return taczlrs$impact; }
    @Inject(method = "onHitEntity", at = @At("RETURN"))
    private void taczlrs$entityImpact(TacHitResult hit, Vec3 from, Vec3 to, CallbackInfo ci) {
        if (taczlrs$capture && (((KineticBulletAccessor) this).taczlrs$getPierce() <= 1 || ((EntityKineticBullet) (Object) this).isRemoved()))
            taczlrs$impact = hit.getLocation();
    }
    @Inject(method = "onHitBlock", at = @At("RETURN"))
    private void taczlrs$blockImpact(BlockHitResult hit, Vec3 from, Vec3 to, CallbackInfo ci) {
        if (taczlrs$capture && ((EntityKineticBullet) (Object) this).isRemoved()) taczlrs$impact = hit.getLocation();
    }
}
