package org.techhouse.ops.resp;

import org.techhouse.log.Logger;

// Holds the logger that OperationResponse cannot: the EJson reflection serializer emits every
// declared field, statics included, so a Logger field on a response class serializes the whole
// Logger into every response of that type.
final class ResponseLog {
    static final Logger LOGGER = Logger.logFor(OperationResponse.class);

    private ResponseLog() {
    }
}
