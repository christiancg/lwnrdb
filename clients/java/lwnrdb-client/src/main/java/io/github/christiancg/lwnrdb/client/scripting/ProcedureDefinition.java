package io.github.christiancg.lwnrdb.client.scripting;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class ProcedureDefinition {
    private final String name;
    private final String script;
    private final String description;
    private final Boolean enabled;
    private final Long ifVersion;

    private ProcedureDefinition(Builder builder) {
        this.name = builder.name;
        this.script = builder.script;
        this.description = builder.description;
        this.enabled = builder.enabled;
        this.ifVersion = builder.ifVersion;
    }

    public static Builder builder(String name, String script) {
        return new Builder(name, script);
    }

    public String name() {
        return name;
    }

    public String script() {
        return script;
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
        private final String script;
        private String description;
        private Boolean enabled;
        private Long ifVersion;

        private Builder(String name, String script) {
            this.name = Objects.requireNonNull(name, "name");
            this.script = Objects.requireNonNull(script, "script");
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

        public ProcedureDefinition build() {
            return new ProcedureDefinition(this);
        }
    }
}
