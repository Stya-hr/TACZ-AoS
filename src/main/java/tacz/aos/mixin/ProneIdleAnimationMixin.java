package tacz.aos.mixin;

import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import tacz.aos.client.ProneAimStability;

/** Suppress only TACZ's passive idle loop, preserving static grip, firing, reload and bolt tracks. */
@Mixin(value = ObjectAnimationRunner.class, remap = false)
public abstract class ProneIdleAnimationMixin {
    @Shadow @Final private ObjectAnimation animation;
    @Shadow private boolean running;
    @Shadow private boolean isTransitioning;
    @Shadow private long progressNs;

    @ModifyVariable(method = "updateProgress(J)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long tacz_aos$steadyIdle(long elapsed) {
        if (running && !isTransitioning && "idle".equals(animation.name) && ProneAimStability.active()) {
            progressNs = 0;
            return 0;
        }
        // update() still refreshes lastUpdateNs: standing up cannot accumulate a delayed time jump.
        return elapsed;
    }
}
