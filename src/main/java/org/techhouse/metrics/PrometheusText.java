package org.techhouse.metrics;

import java.util.HashSet;
import java.util.Set;

public final class PrometheusText {
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    public static final String GAUGE = "gauge";
    public static final String COUNTER = "counter";
    public static final String HISTOGRAM = "histogram";
    private static final double LARGEST_EXACT_INTEGER = 9_007_199_254_740_992d;
    private static final int INITIAL_CAPACITY = 16 * 1024;
    @SuppressWarnings("PMD.AvoidStringBufferField")
    private final StringBuilder out = new StringBuilder(INITIAL_CAPACITY);
    private final Set<String> families = new HashSet<>();
    private String openFamily;

    public PrometheusText family(String name, String type, String help) {
        if (!families.add(name)) {
            throw new IllegalStateException("The metric family " + name + " was already written");
        }
        openFamily = name;
        out.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        return this;
    }

    public PrometheusText sample(String name, double value, String... labelPairs) {
        requireInOpenFamily(name);
        out.append(name);
        appendLabels(labelPairs, null);
        out.append(' ').append(formatValue(value)).append('\n');
        return this;
    }

    public PrometheusText histogram(String name, Histogram histogram, String... labelPairs) {
        requireInOpenFamily(name);
        final var snapshot = histogram.snapshot();
        final var bounds = Histogram.boundLabels();
        for (var i = 0; i < bounds.size(); i++) {
            bucket(name, labelPairs, bounds.get(i), snapshot.countAtMost(i));
        }
        bucket(name, labelPairs, "+Inf", snapshot.count());
        out.append(name).append("_sum");
        appendLabels(labelPairs, null);
        out.append(' ').append(formatValue(snapshot.sumSeconds())).append('\n');
        out.append(name).append("_count");
        appendLabels(labelPairs, null);
        out.append(' ').append(snapshot.count()).append('\n');
        return this;
    }

    private void bucket(String name, String[] labelPairs, String bound, long count) {
        out.append(name).append("_bucket");
        appendLabels(labelPairs, bound);
        out.append(' ').append(count).append('\n');
    }

    public String text() {
        return out.toString();
    }

    private void requireInOpenFamily(String name) {
        if (openFamily == null || !(name.equals(openFamily) || name.startsWith(openFamily + "_"))) {
            throw new IllegalStateException("The sample " + name + " is outside the open family " + openFamily);
        }
    }

    private void appendLabels(String[] labelPairs, String bound) {
        if (labelPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Labels come in name and value pairs");
        }
        if (labelPairs.length == 0 && bound == null) {
            return;
        }
        out.append('{');
        for (var i = 0; i < labelPairs.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append(labelPairs[i]).append("=\"").append(escapeLabelValue(labelPairs[i + 1])).append('"');
        }
        if (bound != null) {
            if (labelPairs.length > 0) {
                out.append(',');
            }
            out.append("le=\"").append(bound).append('"');
        }
        out.append('}');
    }

    static String formatValue(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        if (value == Math.rint(value) && Math.abs(value) < LARGEST_EXACT_INTEGER) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    static String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String escapeHelp(String help) {
        return help.replace("\\", "\\\\").replace("\n", "\\n");
    }
}
