package tacz.aos.trails;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import tacz.aos.TaczArtOfSniping;

public final class FeedbackSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, TaczArtOfSniping.MOD_ID);
    public static final RegistryObject<SoundEvent> FLYBY = SOUNDS.register("bullet_flyby", () -> SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation(TaczArtOfSniping.MOD_ID, "bullet_flyby")));
    public static final RegistryObject<SoundEvent> DISTANT_GUNSHOT = SOUNDS.register("distant_gunshot", () -> SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation(TaczArtOfSniping.MOD_ID, "distant_gunshot")));
    private FeedbackSounds() {}
    public static void register(IEventBus bus) { SOUNDS.register(bus); }
}
