package tacz.longrangesniping.wind;

public record WindSettings(boolean enabled, boolean weatherEffects, long seedSalt,
                           Profile clear, Profile rain, Profile thunder) {
    public record Profile(int periodTicks, double meanSpeed, double maxSpeed) {
        public Profile {
            if (periodTicks < 20 || periodTicks > 24000 || !Double.isFinite(meanSpeed) || !Double.isFinite(maxSpeed)
                    || meanSpeed < 0 || maxSpeed < meanSpeed || maxSpeed > 30)
                throw new IllegalArgumentException("Wind requires period 20..24000 and 0 <= meanSpeed <= maxSpeed <= 30");
        }
    }
    public static final WindSettings DEFAULT = new WindSettings(true, true, 0,
            new Profile(4000, 4, 5), new Profile(2000, 8, 9), new Profile(1000, 12, 14));
    public static final WindSettings DISABLED = new WindSettings(false, false, 0, DEFAULT.clear, DEFAULT.rain, DEFAULT.thunder);
    public Profile profile(int weather) { return weather == 2 ? thunder : weather == 1 ? rain : clear; }
}
