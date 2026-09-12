package tacz.aos.occlusion;

/** Visual occlusion geometry only; not a terrain or collision snapshot. */
public record OccluderBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public OccluderBox {
        if (!Double.isFinite(minX+minY+minZ+maxX+maxY+maxZ) || minX>=maxX || minY>=maxY || minZ>=maxZ
                || Math.max(Math.abs(minX),Math.abs(maxX))>30_000_064 || Math.max(Math.abs(minZ),Math.abs(maxZ))>30_000_064
                || Math.abs(minY)>30_000_064 || Math.abs(maxY)>30_000_064) throw new IllegalArgumentException("Invalid occluder");
    }
    public OccluderBox move(double x,double y,double z) {return new OccluderBox(minX+x,minY+y,minZ+z,maxX+x,maxY+y,maxZ+z);}
}
