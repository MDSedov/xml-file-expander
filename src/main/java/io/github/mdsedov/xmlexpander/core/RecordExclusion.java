package io.github.mdsedov.xmlexpander.core;

/** Keeps matching source records in the output but does not create copies of them. */
public record RecordExclusion(String fieldPath, String value) {

    public RecordExclusion {
        fieldPath = ExpansionOptions.normalizeRelativePath(fieldPath);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Значение условия исключения не может быть пустым");
        }
        value = value.strip();
    }

    public static RecordExclusion parse(String condition) {
        int separator = condition == null ? -1 : condition.indexOf('=');
        if (separator <= 0) {
            throw new IllegalArgumentException(
                    "Укажите запись без дополнительных копий в формате путь=значение, "
                            + "например IDOBJ=10000001");
        }
        return new RecordExclusion(
                condition.substring(0, separator), condition.substring(separator + 1));
    }
}
