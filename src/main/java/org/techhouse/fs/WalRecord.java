package org.techhouse.fs;

import java.util.List;

record WalRecord(long seq, List<WalChange> changes) {
}
