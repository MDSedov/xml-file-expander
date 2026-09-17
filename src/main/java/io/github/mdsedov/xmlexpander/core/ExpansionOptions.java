package io.github.mdsedov.xmlexpander.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public record ExpansionOptions(
        long targetSizeBytes,
        Long fixedExtraCopies,
        List<String> targetPaths,
        String autoParent,
        String autoItem,
        List<String> uniqueFields,
        Charset encoding,
        List<RecordExclusion> recordExclusions,
        List<BranchReference> branchReferences) {

    public static final String DEFAULT_AUTO_PARENT = "/asx:abap/asx:values";
    public static final String DEFAULT_AUTO_ITEM = "item";

    public ExpansionOptions {
        targetPaths = targetPaths == null
                ? List.of()
                : targetPaths.stream().map(ExpansionOptions::normalizeAbsolutePath).distinct().toList();
        uniqueFields = uniqueFields == null
                ? List.of()
                : uniqueFields.stream().map(ExpansionOptions::normalizeRelativePath).distinct().toList();
        autoParent = normalizeAbsolutePath(
                autoParent == null || autoParent.isBlank() ? DEFAULT_AUTO_PARENT : autoParent);
        autoItem = autoItem == null || autoItem.isBlank() ? DEFAULT_AUTO_ITEM : autoItem.trim();
        encoding = Objects.requireNonNullElse(encoding, StandardCharsets.UTF_8);
        recordExclusions = recordExclusions == null ? List.of() : List.copyOf(recordExclusions);
        branchReferences = branchReferences == null ? null : List.copyOf(branchReferences);

        if (autoItem.contains("/")) {
            throw new IllegalArgumentException("Имя элемента записи не должно содержать '/'");
        }
        if (fixedExtraCopies != null && fixedExtraCopies < 0) {
            throw new IllegalArgumentException("Количество дополнительных копий не может быть отрицательным");
        }
        if (fixedExtraCopies == null && targetSizeBytes <= 0) {
            throw new IllegalArgumentException("Целевой размер должен быть больше нуля");
        }
    }

    public ExpansionOptions(
            long targetSizeBytes,
            Long fixedExtraCopies,
            List<String> targetPaths,
            String autoParent,
            String autoItem,
            List<String> uniqueFields,
            Charset encoding) {
        this(targetSizeBytes, fixedExtraCopies, targetPaths, autoParent, autoItem,
                uniqueFields, encoding, List.of(), null);
    }

    public ExpansionOptions withRecordExclusions(List<RecordExclusion> exclusions) {
        return new ExpansionOptions(targetSizeBytes, fixedExtraCopies, targetPaths,
                autoParent, autoItem, uniqueFields, encoding, exclusions, branchReferences);
    }

    public ExpansionOptions withSapBranches(List<BranchReference> references) {
        return new ExpansionOptions(targetSizeBytes, fixedExtraCopies, targetPaths,
                autoParent, autoItem, uniqueFields, encoding, recordExclusions, references);
    }

    public boolean branchMode() {
        return branchReferences != null;
    }

    public static ExpansionOptions targetSize(
            long targetSizeBytes,
            List<String> targetPaths,
            String autoParent,
            String autoItem,
            List<String> uniqueFields) {
        return new ExpansionOptions(
                targetSizeBytes,
                null,
                targetPaths,
                autoParent,
                autoItem,
                uniqueFields,
                StandardCharsets.UTF_8);
    }

    public static ExpansionOptions fixedCopies(
            long copies,
            List<String> targetPaths,
            String autoParent,
            String autoItem,
            List<String> uniqueFields) {
        return new ExpansionOptions(
                0,
                copies,
                targetPaths,
                autoParent,
                autoItem,
                uniqueFields,
                StandardCharsets.UTF_8);
    }

    public boolean autoDiscovery() {
        return targetPaths.isEmpty();
    }

    public boolean fixedCopiesMode() {
        return fixedExtraCopies != null;
    }

    private static String normalizeAbsolutePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Путь XML не может быть пустым");
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String normalizeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Путь уникального поля не может быть пустым");
        }
        String normalized = value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Путь уникального поля не может быть пустым");
        }
        return normalized;
    }
}
