package org.techhouse.ops;

import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class SchemaPatternCaps {
    private static final Logger logger = Logger.logFor(SchemaPatternCaps.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Configuration configuration = Configuration.getInstance();

    public record Violation(ErrorCode code, String message) {
    }

    private SchemaPatternCaps() {
    }

    public static Violation violationOf(JsonObject schema) {
        final var maxPatterns = configuration.intValue(ConfigKey.SCHEMA_MAX_PATTERNS);
        if (eJson.schemaPatternCount(schema) > maxPatterns) {
            return new Violation(ErrorCode.SCHEMA_TOO_MANY_PATTERNS,
                    ErrorCode.SCHEMA_TOO_MANY_PATTERNS.getDefaultMessage() + ": it has more than " + maxPatterns
                            + " patterns");
        }
        final var maxPatternLength = configuration.intValue(ConfigKey.SCHEMA_MAX_PATTERN_LENGTH);
        if (eJson.longestSchemaPattern(schema) > maxPatternLength) {
            return new Violation(ErrorCode.SCHEMA_PATTERN_TOO_LONG,
                    ErrorCode.SCHEMA_PATTERN_TOO_LONG.getDefaultMessage() + ": a pattern is longer than "
                            + maxPatternLength + " characters");
        }
        return null;
    }

    public static void warnWhenOver(String what, JsonObject schema) {
        final var violation = violationOf(schema);
        if (violation != null) {
            logger.warning(what + " although it is over this node's limits (" + violation.message()
                    + "); a pattern longer than schemaMaxPatternLength is compiled on every use and never cached");
        }
    }
}
