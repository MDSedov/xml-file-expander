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
        String copyStrategy,
        BranchSummary branchSummary,
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
                context.options().branchMode() ? "branches" : "records",
                context.plan().branches() == null ? null : new BranchSummary(
                        context.plan().branches().rootCount(),
                        context.plan().branches().copiedPersonCount(),
                        context.plan().branches().preservedPersonCount()),
                context.suggestedOutputPath().toString(),
                context.plan().targetPaths().stream()
                        .map(path -> PathResponse.from(path, context.plan().repeatableRecordBytes()))
                        .toList());
    }

    record BranchSummary(int preservedRoots, long copiedPersonsPerCopy, long preservedPersons) { }

    record PathResponse(
            String path,
            long recordCount,
            long excludedRecordCount,
            long recordBytes,
            double byteSharePercent,
            long residualExtraBytes) {

        static PathResponse from(TargetPathPlan path, long totalBytes) {
            double share = totalBytes == 0 ? 0 : path.recordBytes() * 100.0 / totalBytes;
            return new PathResponse(
                    path.path(),
                    path.recordCount(),
                    path.excludedRecordCount(),
                    path.recordBytes(),
                    share,
                    path.residualExtraBytes());
        }
    }
}
