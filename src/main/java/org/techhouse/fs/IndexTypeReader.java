package org.techhouse.fs;

import java.io.IOException;
import java.util.List;
import org.techhouse.data.FieldIndexEntry;

@FunctionalInterface
public interface IndexTypeReader {
    List<? extends FieldIndexEntry<?>> read() throws IOException;
}
