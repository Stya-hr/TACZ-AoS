package tacz.aos.mixin;

import com.tacz.guns.sound.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SoundManager.class, remap = false)
public abstract class DistantGunshotMixin {
    @Inject(method = "sendSoundToNearby", at = @At("HEAD"), cancellable = true)
    private static void tacz_aos$propagate(LivingEntity source, int distance, ResourceLocation gun, ResourceLocation display,
                                         String name, float volume, float pitch, CallbackInfo ci) {
        if (tacz.aos.trails.DelayedGunshots.INSTANCE.replace(source, distance, gun, display, name, volume, pitch)) ci.cancel();
    }
}
