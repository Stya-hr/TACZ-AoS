package tacz.aos.trails;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import tacz.aos.TaczArtOfSniping;

public final class FeedbackSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, TaczArtOfSniping.MOD_ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> FLYBY = SOUNDS.register("bullet_flyby", SoundEvent::createVariableRangeEvent);
    private FeedbackSounds() {}
    public static void register(IEventBus bus) { SOUNDS.register(bus); }
}
