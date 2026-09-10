package tacz.longrangesniping.scoping;
/** Tick-based angular filter. No distance-dependent target-coordinate threshold. */
public final class AimStability {
    private double x,y,z,speed;
    private long last=Long.MIN_VALUE, since;
    private boolean slow;
    public boolean update(double nx,double ny,double nz,long tick,double threshold,int ticks) {
        if(last==Long.MIN_VALUE || tick-last>2 || tick<=last) {x=nx;y=ny;z=nz;last=since=tick;speed=0;slow=false;return false;}
        double angular=Math.toDegrees(Math.acos(Math.clamp(x*nx+y*ny+z*nz,-1,1)))*20/(tick-last);
        speed=angular*.65+speed*.35;
        boolean now=speed<=threshold*(slow?1.25:1);
        if(!now || !slow) since=tick;
        slow=now;x=nx;y=ny;z=nz;last=tick;
        return slow && tick-since>=ticks;
    }
}
