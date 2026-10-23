import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

public class ProfileReport {
    private static final String PROJECT_PACKAGE = "org.techhouse";
    private static final String CLIENT_MARKER = "[lwnrdb]";
    private static final String STARTUP = "initializing";
    private static final double MEBIBYTE = 1024.0 * 1024.0;

    private record Window(String name, Instant from, Instant to) {
        boolean contains(Instant moment) {
            return !moment.isBefore(from) && moment.isBefore(to);
        }
    }

    private static final class Tally {
        private final Map<String, Long> counts = new HashMap<>();

        void add(String key, long amount) {
            counts.merge(key, amount, Long::sum);
        }

        void print(String title, long denominator, double unit, String suffix, int limit) {
            System.out.println(title);
            counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                    .forEach(entry -> System.out.printf("  %5.1f%%  %9.1f %s  %s%n",
                            denominator == 0 ? 0.0 : 100.0 * entry.getValue() / denominator, entry.getValue() / unit,
                            suffix, entry.getKey()));
        }
    }

    private static final class PhaseStats {
        long samples;
        long allocated;
        final Tally self = new Tally();
        final Tally owner = new Tally();
        final Tally inclusive = new Tally();
        final Tally matches = new Tally();
        final Tally allocationOwner = new Tally();
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length < 2) {
            System.err.println("usage: java ProfileReport.java <recording.jfr> <client.log> [--top N] [match ...]");
            System.exit(2);
        }
        var top = 12;
        final var patterns = new ArrayList<String>();
        for (var i = 2; i < arguments.length; i++) {
            if (arguments[i].equals("--top")) {
                top = Integer.parseInt(arguments[++i]);
            } else {
                patterns.add(arguments[i]);
            }
        }
        final var windows = windowsOf(Path.of(arguments[1]));
        final var stats = new HashMap<String, PhaseStats>();
        windows.forEach(window -> stats.put(window.name(), new PhaseStats()));
        try (var recording = new RecordingFile(Path.of(arguments[0]))) {
            while (recording.hasMoreEvents()) {
                final var event = recording.readEvent();
                final var window = windowOf(windows, event.getStartTime());
                if (window != null) {
                    record(stats.get(window.name()), event, patterns);
                }
            }
        }
        for (final var window : windows) {
            report(window, stats.get(window.name()), patterns, top);
        }
    }

    private static List<Window> windowsOf(Path log) throws IOException {
        final var windows = new ArrayList<Window>();
        Instant previous = null;
        for (final var line : Files.readAllLines(log)) {
            final var parts = line.split(" ", 3);
            if (parts.length < 3 || !parts[1].equals(CLIENT_MARKER)) {
                continue;
            }
            final var moment = OffsetDateTime.parse(parts[0]).toInstant();
            final var phase = parts[2].trim().split("\\s+")[0];
            if (previous != null && !phase.equals(STARTUP)) {
                windows.add(new Window(phase, previous, moment));
            }
            previous = moment;
        }
        return windows;
    }

    private static Window windowOf(List<Window> windows, Instant moment) {
        for (final var window : windows) {
            if (window.contains(moment)) {
                return window;
            }
        }
        return null;
    }

    private static String name(RecordedFrame frame) {
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    private static String ownerOf(List<RecordedFrame> frames) {
        for (final var frame : frames) {
            if (name(frame).startsWith(PROJECT_PACKAGE)) {
                return name(frame);
            }
        }
        return "(jdk only)";
    }

    private static void record(PhaseStats stats, RecordedEvent event, List<String> patterns) {
        final var type = event.getEventType().getName();
        if (event.getStackTrace() == null || event.getStackTrace().getFrames().isEmpty()) {
            return;
        }
        final var frames = event.getStackTrace().getFrames();
        if (type.equals("jdk.ExecutionSample")) {
            stats.samples++;
            stats.self.add(name(frames.getFirst()), 1);
            stats.owner.add(ownerOf(frames), 1);
            final Set<String> seen = new HashSet<>();
            for (final var frame : frames) {
                final var frameName = name(frame);
                if (frameName.startsWith(PROJECT_PACKAGE) && seen.add(frameName)) {
                    stats.inclusive.add(frameName, 1);
                }
                for (final var pattern : patterns) {
                    if (frameName.contains(pattern) && seen.add("match:" + pattern)) {
                        stats.matches.add(pattern, 1);
                    }
                }
            }
        } else if (type.equals("jdk.ObjectAllocationSample")) {
            final var weight = event.getLong("weight");
            stats.allocated += weight;
            stats.allocationOwner.add(ownerOf(frames), weight);
        }
    }

    private static void report(Window window, PhaseStats stats, List<String> patterns, int top) {
        final var seconds = Duration.between(window.from(), window.to()).toMillis() / 1000.0;
        System.out.printf("%n=== %s  %.1fs  %d cpu samples  %.0f MiB sampled allocation%n", window.name(), seconds,
                stats.samples, stats.allocated / MEBIBYTE);
        stats.self.print("-- self time", stats.samples, 1, "smp", top);
        stats.owner.print("-- self time attributed to the nearest project frame", stats.samples, 1, "smp", top);
        stats.inclusive.print("-- inclusive time of project methods", stats.samples, 1, "smp", top);
        if (!patterns.isEmpty()) {
            System.out.println("-- inclusive share of the requested patterns");
            for (final var pattern : patterns) {
                final var hits = stats.matches.counts.getOrDefault(pattern, 0L);
                System.out.printf("  %5.1f%%  %s%n", stats.samples == 0 ? 0.0 : 100.0 * hits / stats.samples, pattern);
            }
        }
        stats.allocationOwner.print("-- sampled allocation by nearest project frame", stats.allocated, MEBIBYTE, "MiB",
                top);
    }
}
