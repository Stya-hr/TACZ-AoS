package tacz.longrangesniping.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tacz.longrangesniping.scoping.ScopeActivity;

@Mixin(Mob.class)
public abstract class ScopedMobActivityMixin {
    @Redirect(method = "checkDespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;removeWhenFarAway(D)Z"))
    private boolean taczlrs$keepObservedMob(Mob mob, double distance) {
        // Leave peaceful difficulty, event overrides and persistence flags untouched.
        return !ScopeActivity.INSTANCE.active(mob) && mob.removeWhenFarAway(distance);
    }

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void taczlrs$allowIdleGoals(CallbackInfo ci) {
        var mob = (Mob) (Object) this;
        if (ScopeActivity.INSTANCE.active(mob)) mob.setNoActionTime(0);
    }
}
