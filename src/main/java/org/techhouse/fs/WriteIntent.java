package org.techhouse.fs;

import org.techhouse.data.PkIndexEntry;

sealed interface WriteIntent permits WriteIntent.Replace, WriteIntent.Delete, WriteIntent.Vacuum {
    record Replace(String id, long page, long position, long length, long version, long targetPage,
            long targetLengthBefore) implements WriteIntent {

        static Replace of(PkIndexEntry oldRow, long position, long targetPage, long targetLengthBefore) {
            return new Replace(oldRow.getValue(), oldRow.getPage(), position, oldRow.getLength(), oldRow.getVersion(),
                    targetPage, targetLengthBefore);
        }

        boolean isRow(PkIndexEntry row) {
            return row != null && row.getPage() == page && row.getPosition() == position && row.getLength() == length
                    && row.getVersion() == version;
        }
    }

    record Delete(String id, long page, long position, long length, long version) implements WriteIntent {
        static Delete of(PkIndexEntry row) {
            return new Delete(row.getValue(), row.getPage(), row.getPosition(), row.getLength(), row.getVersion());
        }

        PkIndexEntry preOpRow(String dbName, String collName) {
            return new PkIndexEntry(dbName, collName, id, position, length, page, version);
        }
    }

    record Vacuum(long page) implements WriteIntent {
    }
}
