package org.techhouse.ops.resp;

import org.techhouse.log.Logger;

// The EJson reflection serializer emits every declared field, statics included, so a Logger field on a
// response class would serialize the whole Logger into every response of that type.
final class ResponseLog {
    static final Logger LOGGER = Logger.logFor(OperationResponse.class);

    private ResponseLog() {
    }
}
