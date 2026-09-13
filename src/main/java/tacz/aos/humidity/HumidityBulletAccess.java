package tacz.aos.humidity;

/** Pins this launch's air-density correction; native spawn data already synchronizes the resulting friction. */
public interface HumidityBulletAccess {
    float tacz_aos$humidFriction(float base, double density);
}
