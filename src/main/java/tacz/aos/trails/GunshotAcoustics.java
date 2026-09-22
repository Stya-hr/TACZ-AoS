package tacz.aos.trails;

/** Gameplay attenuation with a physical propagation delay; one block is one metre. */
public final class GunshotAcoustics {
    private GunshotAcoustics() {}
    public static int delayTicks(double distance, double speed) {
        return Math.max(1, (int) Math.ceil(distance * 20 / speed));
    }
    public static float volume(double distance, double gain) {
        return (float) net.minecraft.util.Mth.clamp(gain * Math.pow(128 / Math.max(128, distance), .65)
                * Math.exp(-distance / 6000), 0, 1);
    }
}
