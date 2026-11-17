package io.github.christiancg.lwnrdb.client.scripting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class TriggerDefinition {
    private final String name;
    private final String procedureName;
    private final List<TriggerEvent> events;
    private final TriggerTiming timing;
    private final TriggerMode mode;
    private final Boolean allowCascade;
    private final Boolean enabled;
    private final Long ifVersion;

    private TriggerDefinition(Builder builder) {
        if (builder.events.isEmpty()) {
            throw new IllegalArgumentException("a trigger needs at least one event");
        }
        this.name = builder.name;
        this.procedureName = builder.procedureName;
        this.events = List.copyOf(builder.events);
        this.timing = builder.timing;
        this.mode = builder.mode;
        this.allowCascade = builder.allowCascade;
        this.enabled = builder.enabled;
        this.ifVersion = builder.ifVersion;
    }

    public static Builder builder(String name, String procedureName) {
        return new Builder(name, procedureName);
    }

    public String name() {
        return name;
    }

    public String procedureName() {
        return procedureName;
    }

    public List<TriggerEvent> events() {
        return events;
    }

    public Optional<TriggerTiming> timing() {
        return Optional.ofNullable(timing);
    }

    public Optional<TriggerMode> mode() {
        return Optional.ofNullable(mode);
    }

    public Optional<Boolean> allowCascade() {
        return Optional.ofNullable(allowCascade);
    }

    public Optional<Boolean> enabled() {
        return Optional.ofNullable(enabled);
    }

    public OptionalLong ifVersion() {
        return ifVersion == null ? OptionalLong.empty() : OptionalLong.of(ifVersion);
    }

    public static final class Builder {
        private final String name;
        private final String procedureName;
        private final List<TriggerEvent> events = new ArrayList<>();
        private TriggerTiming timing;
        private TriggerMode mode;
        private Boolean allowCascade;
        private Boolean enabled;
        private Long ifVersion;

        private Builder(String name, String procedureName) {
            this.name = Objects.requireNonNull(name, "name");
            this.procedureName = Objects.requireNonNull(procedureName, "procedureName");
        }

        public Builder events(TriggerEvent... events) {
            this.events.addAll(Arrays.asList(events));
            return this;
        }

        public Builder timing(TriggerTiming timing) {
            this.timing = Objects.requireNonNull(timing, "timing");
            return this;
        }

        public Builder mode(TriggerMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder allowCascade(boolean allowCascade) {
            this.allowCascade = allowCascade;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder ifVersion(long version) {
            this.ifVersion = version;
            return this;
        }

        public TriggerDefinition build() {
            return new TriggerDefinition(this);
        }
    }
}
