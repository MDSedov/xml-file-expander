package io.github.mdsedov.xmlexpander.web;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import io.github.mdsedov.xmlexpander.core.ExpansionOptions;
import io.github.mdsedov.xmlexpander.core.ExpansionPlan;

record PlanContext(
        UUID id,
        Path uploadedFile,
        String originalFileName,
        long uploadedBytes,
        ExpansionOptions options,
        ExpansionPlan plan,
        Path suggestedOutputPath,
        Instant createdAt) {
}
