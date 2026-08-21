package io.github.mdsedov.xmlexpander.core;

import java.nio.file.Path;

public record ExpansionResult(
        Path outputPath,
        long outputBytes,
        long duplicatesWritten,
        long mutatedFields) {
}
