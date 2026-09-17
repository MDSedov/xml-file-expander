package io.github.mdsedov.xmlexpander.core;

public record TargetPathPlan(
        String path,
        long recordCount,
        long excludedRecordCount,
        long recordBytes,
        long maxRecordBytes,
        long residualExtraBytes) {
}
