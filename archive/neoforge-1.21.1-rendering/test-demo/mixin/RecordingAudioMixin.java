package tacz.aos.test.demo.mixin;
import tacz.aos.test.demo.DemoCapture;
import com.mojang.blaze3d.audio.Library;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.openal.*;
import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;
/** Only loaded by the opt-in recording run. Each client has its own real OpenAL mixer. */
@Mixin(Library.class)
public abstract class RecordingAudioMixin {
 @Inject(method="openDeviceOrFallback",at=@At("HEAD"),cancellable=true)
 private static void device(String name, CallbackInfoReturnable<Long> ci) {
  long device=SOFTLoopback.alcLoopbackOpenDeviceSOFT((String)null);
  if(device==0) throw new IllegalStateException("OpenAL loopback unavailable");
  DemoCapture.device=device; ci.setReturnValue(device);
 }
 @Inject(method="setHrtf",at=@At("HEAD"),cancellable=true)
 private void noReset(boolean enabled,CallbackInfo ci){ci.cancel();}
 @Redirect(method="init",at=@At(value="INVOKE",target="Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"))
 private long context(long device,IntBuffer ignored){
  try(var stack=MemoryStack.stackPush()) {
   return ALC10.alcCreateContext(device,stack.ints(ALC10.ALC_FREQUENCY,48000,
    SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT,SOFTLoopback.ALC_STEREO_SOFT,
    SOFTLoopback.ALC_FORMAT_TYPE_SOFT,SOFTLoopback.ALC_SHORT_SOFT,0));
  }
 }
 @Inject(method="init",at=@At("RETURN"))
 private void start(String name,boolean hrtf,CallbackInfo ci){DemoCapture.startAudio();}
 @Inject(method="cleanup",at=@At("HEAD"))
 private void stop(CallbackInfo ci){DemoCapture.stopAudio();}
}
