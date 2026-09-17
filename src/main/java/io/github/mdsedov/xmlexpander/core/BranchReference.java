package io.github.mdsedov.xmlexpander.core;

/** An explicitly declared SAP reference; values are never inferred from coincidental matches. */
public record BranchReference(String collection, String fieldPath, String domain) {
    public BranchReference {
        if (!"ET_ORG".equals(collection) && !"ET_PERSON".equals(collection)) {
            throw new IllegalArgumentException("Связи поддерживаются для ET_ORG и ET_PERSON");
        }
        fieldPath = ExpansionOptions.normalizeRelativePath(fieldPath);
        if (!"org".equals(domain) && !"person".equals(domain)) {
            throw new IllegalArgumentException("Область ID должна быть org или person");
        }
    }

    public static BranchReference parse(String line) {
        int slash = line.indexOf('/');
        int equals = line.lastIndexOf('=');
        if (slash < 1 || equals <= slash + 1) {
            throw new IllegalArgumentException(
                    "Формат связи: ET_PERSON/путь/к/полю=org или ET_ORG/путь/к/полю=person");
        }
        return new BranchReference(line.substring(0, slash).strip(),
                line.substring(slash + 1, equals).strip(), line.substring(equals + 1).strip());
    }
}
