package io.github.mdsedov.xmlexpander.core;

public record ProgressUpdate(String phase, long processedBytes, long totalBytes, String message) {

    public int percentage() {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.max(0, processedBytes * 100 / totalBytes));
    }
}
