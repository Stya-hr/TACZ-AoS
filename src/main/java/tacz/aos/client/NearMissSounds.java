package tacz.aos.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import tacz.aos.network.NearMissPayload;
import tacz.aos.trails.FeedbackSounds;
import tacz.aos.trails.TrailAudience;

public final class NearMissSounds {
    private static long lastTick = Long.MIN_VALUE;
    private static int played;
    private NearMissSounds() {}
    public static void receive(NearMissPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.dimension().location().equals(p.dimension())
                || !ShotTrails.hasLocalChunk((int)Math.floor(p.x()/16), (int)Math.floor(p.z()/16))) return;
        double distance = mc.player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(p.x(),p.y(),p.z()));
        // Recheck at receipt: moving out of range during network transit must not play a distant flyby.
        float volume = Math.min(p.volume(), TrailAudience.flybyVolume(distance));
        if (volume <= 0) return;
        long tick = mc.level.getGameTime();
        if (tick != lastTick) { lastTick = tick; played = 0; }
        if (++played > 4) return;
        // Distance gain is already applied. Keep direction without a second OpenAL distance falloff.
        mc.getSoundManager().play(new SimpleSoundInstance(FeedbackSounds.FLYBY.getId(), SoundSource.PLAYERS,
                volume, 0.94f + mc.level.random.nextFloat() * 0.12f, mc.level.random,
                false, 0, SoundInstance.Attenuation.NONE, p.x(), p.y(), p.z(), false));
    }
}
