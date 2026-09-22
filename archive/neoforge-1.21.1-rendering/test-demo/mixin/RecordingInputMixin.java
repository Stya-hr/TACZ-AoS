package tacz.aos.test.demo.mixin;
import com.tacz.guns.util.InputExtraCheck;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** A second recording client must continue its scripted input without owning the OS foreground window. */
@Mixin(value=InputExtraCheck.class,remap=false)
public abstract class RecordingInputMixin {
 @Inject(method="isInGame",at=@At("HEAD"),cancellable=true)
 private static void backgroundInput(CallbackInfoReturnable<Boolean> ci){var mc=Minecraft.getInstance();ci.setReturnValue(mc.player!=null&&mc.screen==null&&mc.getOverlay()==null);}
}
