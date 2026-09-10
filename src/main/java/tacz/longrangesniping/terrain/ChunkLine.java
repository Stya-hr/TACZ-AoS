package tacz.longrangesniping.terrain;
import java.util.LinkedHashSet;
import java.util.Set;
/** Horizontal supercover: corner/edge contacts cannot jump across an unknown column. */
public final class ChunkLine {
    private ChunkLine() {}
    private static long key(int x,int z) { return (x&0xffffffffL)|((z&0xffffffffL)<<32); }
    public static Set<Long> columns(double ax,double az,double bx,double bz) {
        if(!Double.isFinite(ax+az+bx+bz) || Math.max(Math.abs(ax),Math.abs(bx))>30000000
            || Math.max(Math.abs(az),Math.abs(bz))>30000000 || Math.hypot(bx-ax,bz-az)>32768) throw new IllegalArgumentException("Invalid line");
        Set<Long> out=new LinkedHashSet<>();
        int x=(int)Math.floor(ax/16),z=(int)Math.floor(az/16),ex=(int)Math.floor(bx/16),ez=(int)Math.floor(bz/16);
        double dx=bx-ax,dz=bz-az; int ix=Double.compare(dx,0),iz=Double.compare(dz,0);
        double tx=dx==0?Double.POSITIVE_INFINITY:((ix>0?(x+1)*16.0:x*16.0)-ax)/dx;
        double tz=dz==0?Double.POSITIVE_INFINITY:((iz>0?(z+1)*16.0:z*16.0)-az)/dz;
        for(int i=0;i<8192;i++) {
            out.add(key(x,z));
            if(dx==0 && ax==x*16.0) out.add(key(x-1,z));
            if(dz==0 && az==z*16.0) out.add(key(x,z-1));
            if(x==ex && z==ez) return out;
            if(Math.abs(tx-tz)<1e-12) {
                out.add(key(x+ix,z));out.add(key(x,z+iz));x+=ix;z+=iz;tx+=16/Math.abs(dx);tz+=16/Math.abs(dz);
            } else if(tx<tz) {x+=ix;tx+=16/Math.abs(dx);} else {z+=iz;tz+=16/Math.abs(dz);}
        }
        throw new IllegalArgumentException("Line too long");
    }
}
