package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.ejson.elements.JsonObject;

public record CapturingHostBindings(HostBindings delegate, ConsoleCapture capture) implements HostBindings {

    public static HostBindings wrap(HostBindings delegate, ConsoleCapture capture) {
        return new CapturingHostBindings(delegate, capture);
    }

    @Override
    public JsonObject args() {
        return delegate.args();
    }

    @Override
    public DatabaseAccess database() {
        return delegate.database();
    }

    @Override
    public Consumer<String> console() {
        final var hostSink = delegate.console();
        if (hostSink == null) {
            return capture;
        }
        return line -> {
            capture.accept(line);
            hostSink.accept(line);
        };
    }

    @Override
    public ResourceLimits limits() {
        return delegate.limits();
    }

    @Override
    public boolean strictScriptGoal() {
        return delegate.strictScriptGoal();
    }

    @Override
    public ModuleResolver moduleResolver() {
        return delegate.moduleResolver();
    }

    @Override
    public NetworkAccess network() {
        return delegate.network();
    }

    @Override
    public ZoneId timeZone() {
        return delegate.timeZone();
    }

    @Override
    public Locale locale() {
        return delegate.locale();
    }
}
