package tacz.longrangesniping.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import tacz.longrangesniping.network.NearMissPayload;
import tacz.longrangesniping.trails.FeedbackSounds;
import tacz.longrangesniping.trails.TrailAudience;

public final class NearMissSounds {
    private static long lastTick = Long.MIN_VALUE;
    private static int played;
    private NearMissSounds() {}
    public static void receive(NearMissPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.dimension().location().equals(p.dimension())
                || mc.player.getEyePosition().distanceToSqr(new net.minecraft.world.phys.Vec3(p.x(),p.y(),p.z())) > Math.pow(TrailAudience.HEARING_RADIUS + 4, 2)
                || !ShotTrails.hasLocalChunk((int)Math.floor(p.x()/16), (int)Math.floor(p.z()/16))) return;
        long tick = mc.level.getGameTime();
        if (tick != lastTick) { lastTick = tick; played = 0; }
        if (++played > 4) return;
        mc.level.playLocalSound(p.x(), p.y(), p.z(), FeedbackSounds.FLYBY.get(), SoundSource.PLAYERS,
                p.volume(), 0.94f + mc.level.random.nextFloat() * 0.12f, false);
    }
}
