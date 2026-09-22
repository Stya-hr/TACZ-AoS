package tacz.aos.occlusion;

/** Lazily observed section revisions; no block snapshots or persistent state. */
public interface OcclusionRevision {
    long tacz_aos$occlusionRevision(int section);
}
