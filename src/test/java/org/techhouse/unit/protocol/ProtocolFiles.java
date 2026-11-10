package org.techhouse.unit.protocol;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.techhouse.ops.OperationType;

final class ProtocolFiles {
    static final Path ROOT = Path.of("clients/protocol");
    static final Path OPERATIONS = ROOT.resolve("operations.json");
    static final Path ERROR_CODES = ROOT.resolve("error-codes.json");
    static final Path CUSTOM_TYPES = ROOT.resolve("custom-types.json");
    static final Path REQUEST_FIXTURES = ROOT.resolve("fixtures/requests");
    static final Path RESPONSE_FIXTURES = ROOT.resolve("fixtures/responses");
    static final Set<OperationType> TEST_ONLY_OPERATIONS = EnumSet.of(OperationType.ARM_FAULT,
            OperationType.AWAIT_FAULT, OperationType.RELEASE_FAULT);

    private ProtocolFiles() {
    }

    static List<OperationType> clientFacingOperations() {
        return Arrays.stream(OperationType.values()).filter(type -> !TEST_ONLY_OPERATIONS.contains(type)).toList();
    }
}
