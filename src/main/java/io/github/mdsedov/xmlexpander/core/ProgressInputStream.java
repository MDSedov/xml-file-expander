package io.github.mdsedov.xmlexpander.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class ProgressInputStream extends FilterInputStream {

    private static final long NOTIFY_STEP_BYTES = 1L << 20;

    private final long totalBytes;
    private final String phase;
    private final ProgressListener listener;
    private long count;
    private long nextNotification = NOTIFY_STEP_BYTES;

    ProgressInputStream(
            InputStream inputStream,
            long totalBytes,
            String phase,
            ProgressListener listener) {
        super(inputStream);
        this.totalBytes = totalBytes;
        this.phase = phase;
        this.listener = listener;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            advance(1);
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int read = super.read(bytes, offset, length);
        if (read > 0) {
            advance(read);
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        try {
            listener.onProgress(new ProgressUpdate(phase, count, totalBytes, "Чтение XML завершено"));
        } finally {
            super.close();
        }
    }

    private void advance(int bytes) {
        count += bytes;
        if (count >= nextNotification) {
            listener.onProgress(new ProgressUpdate(phase, count, totalBytes, "Чтение XML"));
            nextNotification = count + NOTIFY_STEP_BYTES;
        }
    }
}
