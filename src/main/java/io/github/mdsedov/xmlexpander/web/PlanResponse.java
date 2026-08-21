package io.github.mdsedov.xmlexpander.web;

import java.util.List;
import java.util.UUID;

import io.github.mdsedov.xmlexpander.core.TargetPathPlan;

record PlanResponse(
        UUID planId,
        String originalFileName,
        long uploadedBytes,
        long originalSerializedBytes,
        long requestedTargetBytes,
        long repeatableRecordBytes,
        long fullExtraCopiesPerRecord,
        long residualExtraBytes,
        long estimatedOutputBytes,
        boolean fixedCopiesMode,
        String suggestedOutputPath,
        List<PathResponse> targetPaths) {

    static PlanResponse from(PlanContext context) {
        return new PlanResponse(
                context.id(),
                context.originalFileName(),
                context.uploadedBytes(),
                context.plan().originalSerializedBytes(),
                context.plan().requestedTargetBytes(),
                context.plan().repeatableRecordBytes(),
                context.plan().fullExtraCopiesPerRecord(),
                context.plan().residualExtraBytes(),
                context.plan().estimatedOutputBytes(),
                context.plan().fixedCopiesMode(),
                context.suggestedOutputPath().toString(),
                context.plan().targetPaths().stream()
                        .map(path -> PathResponse.from(path, context.plan().repeatableRecordBytes()))
                        .toList());
    }

    record PathResponse(
            String path,
            long recordCount,
            long recordBytes,
            double byteSharePercent,
            long residualExtraBytes) {

        static PathResponse from(TargetPathPlan path, long totalBytes) {
            double share = totalBytes == 0 ? 0 : path.recordBytes() * 100.0 / totalBytes;
            return new PathResponse(
                    path.path(),
                    path.recordCount(),
                    path.recordBytes(),
                    share,
                    path.residualExtraBytes());
        }
    }
}
