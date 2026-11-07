package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.ChunkedChannelWrites;

public final class OutsideWrites {
    private OutsideWrites() {
    }

    public static void madeTo(File file) {
        PageLengths.forget(file);
        PkIndexStore.forgetState(file);
        FieldIndexLog.forgetFilesUnder(file.getAbsoluteFile().getParentFile());
        if (file.getName().endsWith(Globals.WAL_EXTENSION)) {
            WriteAheadLog.forgetUnder(file.getAbsoluteFile().getParentFile());
        }
    }

    public static long logEnd(File log) throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(log.toPath())).cleanLength();
    }

    public static void appendToLog(File log, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(log.toPath(), StandardOpenOption.WRITE)) {
            ChunkedChannelWrites.writeAt(channel, logEnd(log), bytes, 0, bytes.length);
        }
    }
}
