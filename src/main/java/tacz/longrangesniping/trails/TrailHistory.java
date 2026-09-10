package tacz.longrangesniping.trails;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded visual history. Each received segment is independent, so dropped updates never bridge a wall or teleport. */
public final class TrailHistory {
    public static final int MAX_SHOTS = 128, MAX_SAMPLES = TrailStyle.LIFETIME_TICKS + 4;
    public record Sample(TrailSegment segment, double start) {}
    private static final class Track {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        int step;
        double start;
        boolean terminal;
    }
    private final LinkedHashMap<Integer, Track> tracks = new LinkedHashMap<>();

    public void accept(TrailSegment segment, double now) {
        var track = tracks.get(segment.shot());
        if (track == null) {
            if (tracks.size() >= MAX_SHOTS) tracks.remove(tracks.keySet().iterator().next());
            track = new Track(); tracks.put(segment.shot(), track);
        }
        if (segment.step() <= track.step || track.terminal) return;
        // About one tick of interpolation; never extrapolate beyond a confirmed endpoint.
        double start = track.samples.isEmpty() ? now : Math.clamp(track.start + segment.step() - track.step, now - 1, now + 1);
        track.samples.addLast(new Sample(segment, start));
        while (track.samples.size() > MAX_SAMPLES) track.samples.removeFirst();
        track.step = segment.step(); track.start = start; track.terminal = segment.terminal();
    }
    public void expire(double now) {
        tracks.values().removeIf(track -> {
            track.samples.removeIf(s -> now - s.start() > 1 + TrailStyle.lifetime(s.segment().speed()));
            return track.samples.isEmpty();
        });
    }
    public List<Sample> samples() { return tracks.values().stream().flatMap(t -> t.samples.stream()).toList(); }
    public boolean isEmpty() { return tracks.isEmpty(); }
    public void clear() { tracks.clear(); }
}
