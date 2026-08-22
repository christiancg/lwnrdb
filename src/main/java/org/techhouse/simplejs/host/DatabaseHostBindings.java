package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;

/**
 * The database's own {@link HostBindings}: the same contract {@code SimpleHostBindings} supplies,
 * with the time zone and locale pinned by configuration ({@code scriptTimeZone}/{@code scriptLocale})
 * so a script answers identically on every cluster node. {@code SimpleHostBindings} deliberately
 * keeps the JVM defaults, which is what leaves the test262 worker's behaviour untouched.
 */
public record DatabaseHostBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
        ResourceLimits limits, ZoneId timeZone, Locale locale) implements HostBindings {

    public static DatabaseHostBindings of(JsonObject args, DatabaseAccess database, Consumer<String> console,
            ResourceLimits limits) {
        final var configuration = Configuration.getInstance();
        return new DatabaseHostBindings(args, database, console, limits, ZoneId.of(configuration.getScriptTimeZone()),
                Locale.forLanguageTag(configuration.getScriptLocale()));
    }
}
