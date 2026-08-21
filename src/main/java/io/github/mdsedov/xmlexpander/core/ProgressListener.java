package io.github.mdsedov.xmlexpander.core;

@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = update -> {
    };

    void onProgress(ProgressUpdate update);
}
