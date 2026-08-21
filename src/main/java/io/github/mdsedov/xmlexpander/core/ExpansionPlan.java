package io.github.mdsedov.xmlexpander.core;

import java.util.List;

public record ExpansionPlan(
        long originalSerializedBytes,
        long requestedTargetBytes,
        long repeatableRecordBytes,
        long fullExtraCopiesPerRecord,
        long residualExtraBytes,
        long estimatedOutputBytes,
        boolean fixedCopiesMode,
        List<TargetPathPlan> targetPaths) {

    public long maximumResidualOvershootBytes() {
        return targetPaths.stream().mapToLong(TargetPathPlan::maxRecordBytes).sum();
    }
}
