package tacz.longrangesniping.ballistics;

import net.minecraft.world.phys.Vec3;

/** Native TACZ tick moves even after an impact; capture the impact solely to clip its visual endpoint. */
public interface BulletVisualAccess {
    void taczlrs$beginVisualStep();
    Vec3 taczlrs$endVisualStep();
}
