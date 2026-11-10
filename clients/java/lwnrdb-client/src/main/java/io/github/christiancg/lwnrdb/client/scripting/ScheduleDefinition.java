package io.github.christiancg.lwnrdb.client.scripting;

import io.github.christiancg.lwnrdb.client.Document;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class ScheduleDefinition {
    private final String name;
    private final String procedureName;
    private final String cron;
    private final Duration interval;
    private final Document args;
    private final Duration timeout;
    private final String description;
    private final Boolean enabled;
    private final Long ifVersion;

    private ScheduleDefinition(Builder builder) {
        if ((builder.cron == null) == (builder.interval == null)) {
            throw new IllegalArgumentException("a schedule takes exactly one of cron and interval");
        }
        this.name = builder.name;
        this.procedureName = builder.procedureName;
        this.cron = builder.cron;
        this.interval = builder.interval;
        this.args = builder.args;
        this.timeout = builder.timeout;
        this.description = builder.description;
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

    public Optional<String> cron() {
        return Optional.ofNullable(cron);
    }

    public Optional<Duration> interval() {
        return Optional.ofNullable(interval);
    }

    public Optional<Document> args() {
        return Optional.ofNullable(args);
    }

    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
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
        private String cron;
        private Duration interval;
        private Document args;
        private Duration timeout;
        private String description;
        private Boolean enabled;
        private Long ifVersion;

        private Builder(String name, String procedureName) {
            this.name = Objects.requireNonNull(name, "name");
            this.procedureName = Objects.requireNonNull(procedureName, "procedureName");
        }

        public Builder cron(String expression) {
            this.cron = Objects.requireNonNull(expression, "expression");
            return this;
        }

        public Builder interval(Duration interval) {
            this.interval = Objects.requireNonNull(interval, "interval");
            return this;
        }

        public Builder args(Document args) {
            this.args = Objects.requireNonNull(args, "args");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
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

        public ScheduleDefinition build() {
            return new ScheduleDefinition(this);
        }
    }
}
