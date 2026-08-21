package io.github.mdsedov.xmlexpander.web;

import java.util.UUID;

record JobSnapshot(
        UUID jobId,
        String status,
        int progress,
        String message,
        String outputPath,
        Long outputBytes,
        Long duplicatesWritten,
        Long mutatedFields,
        String error) {
}
