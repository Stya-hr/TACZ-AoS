package tacz.aos.trails;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded visual history. Each received segment is independent, so dropped updates never bridge a wall or teleport. */
public final class TrailHistory {
    public enum ImpactMode { RETIRE, FADE_SMOKE }
    public static final int MAX_SHOTS = 128, MAX_SAMPLES = (TrailStyle.LIFETIME_TICKS + 4) * 4;
    public record Sample(TrailSegment segment, double start) {}
    private static final class Track {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        int step;
        double start;
        boolean terminal;
        double endsAt = Double.POSITIVE_INFINITY;
    }
    private final LinkedHashMap<Integer, Track> tracks = new LinkedHashMap<>();
    private final ImpactMode impactMode;

    public TrailHistory() { this(ImpactMode.RETIRE); }
    public TrailHistory(ImpactMode impactMode) { this.impactMode = java.util.Objects.requireNonNull(impactMode); }

    public void accept(TrailSegment segment, double now) {
        var track = tracks.get(segment.shot());
        if (track == null) {
            if (tracks.size() >= MAX_SHOTS) tracks.remove(tracks.keySet().iterator().next());
            track = new Track(); tracks.put(segment.shot(), track);
        }
        if (segment.step() < track.step || track.terminal) return;
        if (segment.step() == track.step && track.samples.stream().anyMatch(s -> s.segment().equals(segment))) return;
        // Multiple clipped runs may share a tick; they stay disjoint and share the interpolation time.
        // About one tick of interpolation; never extrapolate beyond a confirmed endpoint.
        double start = track.samples.isEmpty() ? now : Math.clamp(track.start + segment.step() - track.step, now - 1, now + 1);
        // A confirmed stop may finish its last interpolation, but cannot queue another future tick.
        if (segment.terminal()) start = Math.min(start, now);
        boolean stationary = segment.x0() == segment.x1() && segment.y0() == segment.y1() && segment.z0() == segment.z1();
        // A stationary stop is control data, not a fresh puff. Do not restart the age of earlier smoke.
        if (!segment.terminal() || !stationary) track.samples.addLast(new Sample(segment, start));
        while (track.samples.size() > MAX_SAMPLES) track.samples.removeFirst();
        track.step = segment.step(); track.start = start; track.terminal = segment.terminal();
        if (track.terminal) {
            track.endsAt = stationary ? now : start + 1;
        }
    }
    public void expire(double now) {
        tracks.values().removeIf(track -> {
            if (impactMode == ImpactMode.RETIRE && now >= track.endsAt) track.samples.clear();
            track.samples.removeIf(s -> now - s.start() > 1 + TrailStyle.lifetime(s.segment().speed()));
            // Keep a bounded tombstone so a late packet cannot resurrect smoke after impact.
            return track.terminal ? now > track.endsAt + TrailStyle.LIFETIME_TICKS + 1 : track.samples.isEmpty();
        });
    }
    /** Observer smoke fades at its confirmed positions; stopping motion does not erase the wake. */
    public List<Sample> visibleSamples(double now) {
        return tracks.values().stream().filter(t -> smokeVisible(t, now)).flatMap(t -> t.samples.stream()).toList();
    }
    public boolean hasVisibleSamples(double now) {
        return tracks.values().stream().anyMatch(t -> smokeVisible(t, now) && !t.samples.isEmpty());
    }
    private boolean smokeVisible(Track track, double now) {
        return impactMode == ImpactMode.FADE_SMOKE || now < track.endsAt;
    }
    /** Do not leave a glowing head at the impact while the observer's smoke fades. */
    public boolean hasMovingHead(int shot, double now) {
        var track = tracks.get(shot);
        return track != null && !track.samples.isEmpty() && now < track.endsAt && now < track.start + 1;
    }
    public List<Sample> samples() { return tracks.values().stream().flatMap(t -> t.samples.stream()).toList(); }
    public boolean isEmpty() { return tracks.values().stream().allMatch(t -> t.samples.isEmpty()); }
    public void clear() { tracks.clear(); }
}
