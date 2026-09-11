package tacz.aos.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import tacz.aos.network.DistantGunshotPayload;
import tacz.aos.trails.FeedbackSounds;

public final class DistantGunshotSounds {
    private static java.lang.ref.WeakReference<ClientLevel> level = new java.lang.ref.WeakReference<>(null);
    private static long tick;
    private static int count;
    private DistantGunshotSounds() {}
    public static void receive(DistantGunshotPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.dimension().location().equals(p.dimension())) return;
        if (level.get() != mc.level || tick != mc.level.getGameTime()) {
            level = new java.lang.ref.WeakReference<>(mc.level); tick = mc.level.getGameTime(); count = 0;
        }
        if (++count > 4 || p.volume() <= 0) return;
        // Server already applied distance loss. Keep directional positioning without Minecraft's 16-block cutoff.
        mc.getSoundManager().play(new SimpleSoundInstance(FeedbackSounds.DISTANT_GUNSHOT.getId(), SoundSource.PLAYERS,
                p.volume(), p.pitch(), mc.level.random, false, 0, SoundInstance.Attenuation.NONE, p.x(), p.y(), p.z(), false));
    }
}
