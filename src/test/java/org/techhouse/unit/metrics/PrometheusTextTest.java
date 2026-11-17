package org.techhouse.unit.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.metrics.Histogram;
import org.techhouse.metrics.PrometheusText;

public class PrometheusTextTest {

    @Test
    public void test_a_family_writes_help_and_type_once_before_its_samples() {
        final var text = new PrometheusText().family("x_total", PrometheusText.COUNTER, "Things counted.")
                .sample("x_total", 3, "kind", "a").sample("x_total", 4, "kind", "b").text();

        assertEquals("""
                # HELP x_total Things counted.
                # TYPE x_total counter
                x_total{kind="a"} 3
                x_total{kind="b"} 4
                """, text);
    }

    @Test
    public void test_label_values_are_escaped() {
        final var text = new PrometheusText().family("x", PrometheusText.GAUGE, "help")
                .sample("x", 1, "path", "a\\b\"c\nd").text();

        assertTrue(text.contains("x{path=\"a\\\\b\\\"c\\nd\"} 1\n"), text);
    }

    @Test
    public void test_help_text_escapes_backslashes_and_newlines() {
        final var text = new PrometheusText().family("x", PrometheusText.GAUGE, "one\\two\nthree").text();

        assertTrue(text.startsWith("# HELP x one\\\\two\\nthree\n"), text);
    }

    @Test
    public void test_values_are_spelled_as_prometheus_spells_them() {
        final var text = new PrometheusText().family("x", PrometheusText.GAUGE, "help").sample("x", 2.0, "v", "int")
                .sample("x", 0.25, "v", "fraction").sample("x", Double.NaN, "v", "nan")
                .sample("x", Double.POSITIVE_INFINITY, "v", "inf").sample("x", Double.NEGATIVE_INFINITY, "v", "ninf")
                .sample("x", 1e20, "v", "huge").text();

        assertTrue(text.contains("x{v=\"int\"} 2\n"), text);
        assertTrue(text.contains("x{v=\"fraction\"} 0.25\n"), text);
        assertTrue(text.contains("x{v=\"nan\"} NaN\n"), text);
        assertTrue(text.contains("x{v=\"inf\"} +Inf\n"), text);
        assertTrue(text.contains("x{v=\"ninf\"} -Inf\n"), text);
        assertTrue(text.contains("x{v=\"huge\"} 1.0E20\n"), text);
    }

    @Test
    public void test_a_sample_without_labels_has_no_braces() {
        final var text = new PrometheusText().family("x", PrometheusText.GAUGE, "help").sample("x", 7).text();

        assertTrue(text.endsWith("\nx 7\n"), text);
    }

    @Test
    public void test_a_histogram_writes_cumulative_buckets_sum_and_count() {
        final var histogram = new Histogram();
        histogram.observeNanos(TimeUnit.MILLISECONDS.toNanos(2));
        histogram.observeNanos(TimeUnit.SECONDS.toNanos(20));

        final var text = new PrometheusText().family("d_seconds", PrometheusText.HISTOGRAM, "help")
                .histogram("d_seconds", histogram, "op", "SAVE").text();

        assertTrue(text.contains("d_seconds_bucket{op=\"SAVE\",le=\"0.001\"} 0\n"), text);
        assertTrue(text.contains("d_seconds_bucket{op=\"SAVE\",le=\"0.0025\"} 1\n"), text);
        assertTrue(text.contains("d_seconds_bucket{op=\"SAVE\",le=\"10\"} 1\n"), text);
        assertTrue(text.contains("d_seconds_bucket{op=\"SAVE\",le=\"+Inf\"} 2\n"), text);
        assertTrue(text.contains("d_seconds_sum{op=\"SAVE\"} 20.002\n"), text);
        assertTrue(text.contains("d_seconds_count{op=\"SAVE\"} 2\n"), text);
        assertEquals(Histogram.boundLabels().size() + 1, text.split("_bucket").length - 1);
    }

    @Test
    public void test_a_histogram_without_labels_has_only_the_le_label() {
        final var text = new PrometheusText().family("d", PrometheusText.HISTOGRAM, "help")
                .histogram("d", new Histogram()).text();

        assertTrue(text.contains("d_bucket{le=\"+Inf\"} 0\n"), text);
        assertTrue(text.contains("d_sum 0\n"), text);
        assertTrue(text.contains("d_count 0\n"), text);
    }

    @Test
    public void test_a_family_written_twice_is_refused() {
        final var text = new PrometheusText().family("x", PrometheusText.GAUGE, "help");

        assertThrows(IllegalStateException.class, () -> text.family("x", PrometheusText.GAUGE, "again"));
    }

    @Test
    public void test_a_sample_outside_its_family_is_refused() {
        final var empty = new PrometheusText();
        final var text = new PrometheusText().family("x", PrometheusText.GAUGE, "help");

        assertThrows(IllegalStateException.class, () -> empty.sample("x", 1));
        assertThrows(IllegalStateException.class, () -> text.sample("y", 1));
        assertThrows(IllegalStateException.class, () -> text.sample("xy", 1));
    }

    @Test
    public void test_an_odd_label_list_is_refused() {
        final var text = new PrometheusText().family("x", PrometheusText.GAUGE, "help");

        assertThrows(IllegalArgumentException.class, () -> text.sample("x", 1, "lonely"));
    }
}
