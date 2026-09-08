package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TimeZoneStringParser.parseTimeZoneIdentifier;

import org.techhouse.simplejs.exceptions.RangeErrorException;

final class AnnotationParser {
    record Annotations(String timeZoneId, String calendar) {
    }

    static Annotations parseAnnotations(TemporalCursor cursor) {
        String timeZoneId = null;
        String calendar = null;
        var calendarCount = 0;
        var calendarCritical = false;
        var index = 0;
        while (!cursor.atEnd() && cursor.peek() == '[') {
            cursor.advance();
            var critical = false;
            if (!cursor.atEnd() && cursor.peek() == '!') {
                critical = true;
                cursor.advance();
            }
            final var contentStart = cursor.pos;
            while (true) {
                if (cursor.atEnd()) {
                    throw new RangeErrorException("Unterminated Temporal annotation: " + cursor.source);
                }
                if (cursor.peek() == ']') {
                    break;
                }
                cursor.advance();
            }
            final var content = cursor.source.substring(contentStart, cursor.pos);
            cursor.advance();
            final var equalsIndex = content.indexOf('=');
            if (equalsIndex < 0) {
                if (index != 0) {
                    throw new RangeErrorException(
                            "A bare time zone annotation must be the first annotation: " + cursor.source);
                }
                parseTimeZoneIdentifier(content);
                timeZoneId = content;
            } else {
                final var key = content.substring(0, equalsIndex);
                final var value = content.substring(equalsIndex + 1);
                if (!isValidAnnotationKey(key)) {
                    throw new RangeErrorException("Annotation keys must be lowercase: " + cursor.source);
                }
                if ("u-ca".equals(key)) {
                    calendarCount++;
                    if (calendarCount > 1 && (critical || calendarCritical)) {
                        throw new RangeErrorException(
                                "More than one calendar annotation with a critical flag: " + cursor.source);
                    }
                    if (critical) {
                        calendarCritical = true;
                    }
                    if (calendar == null) {
                        calendar = value;
                    }
                } else if (critical) {
                    throw new RangeErrorException("Unknown critical Temporal annotation: " + key);
                }
            }
            index++;
        }
        return new Annotations(timeZoneId, calendar);
    }

    static boolean isValidAnnotationKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (var i = 0; i < key.length(); i++) {
            final var c = key.charAt(i);
            final var valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private AnnotationParser() {
    }
}
