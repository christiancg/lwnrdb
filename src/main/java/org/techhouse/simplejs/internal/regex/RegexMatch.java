package org.techhouse.simplejs.internal.regex;

public final class RegexMatch {
    private final int[] starts;
    private final int[] ends;
    private final String input;

    RegexMatch(int[] starts, int[] ends, String input) {
        this.starts = starts;
        this.ends = ends;
        this.input = input;
    }

    public int start() {
        return starts[0];
    }

    public int end() {
        return ends[0];
    }

    public int start(int group) {
        return starts[group];
    }

    public int end(int group) {
        return ends[group];
    }

    public String group(int group) {
        return starts[group] < 0 ? null : input.substring(starts[group], ends[group]);
    }

    public int groupCount() {
        return starts.length - 1;
    }
}
