package tacz.aos.compat.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import net.neoforged.bus.api.Event;

/** Server-only observation hook. Does not consume ammunition, cancel spawning or apply damage. */
public final class TaczShotObservedEvent extends Event {
    private final EntityKineticBullet bullet;
    private final TaczBridge.ShotContext context;

    public TaczShotObservedEvent(EntityKineticBullet bullet, TaczBridge.ShotContext context) {
        this.bullet = bullet;
        this.context = context;
    }

    public EntityKineticBullet bullet() { return bullet; }
    public TaczBridge.ShotContext context() { return context; }
}
