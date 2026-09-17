package io.github.mdsedov.xmlexpander.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.github.mdsedov.xmlexpander.core.BranchIdAllocator.Key;

/** Metadata only: XML fragments and generated copies are not retained in the plan. */
public final class SapBranchPlan {
    static final String PARENT = "DYN_ATTR/PARENT/item/VALUE";
    static final String SELF = "DYN_ATTR/HRP9110/item/OBJID";
    private final ExpansionOptions options;
    private final Map<String, Map<String, String>> rules = new LinkedHashMap<>();
    private final Map<Key, Node> nodes = new LinkedHashMap<>();
    private final Set<String> roots = new LinkedHashSet<>();
    private final Set<BranchReference> observed = new LinkedHashSet<>();
    private BranchIdAllocator ids;
    private String sourceDigest;
    private boolean personLinksConfigured;

    private SapBranchPlan(ExpansionOptions options) {
        this.options = options;
        if (!options.autoDiscovery()) {
            throw new IllegalArgumentException("Для ветвей SAP оставьте явные пути записей пустыми: требуется всё дерево");
        }
        if (!options.encoding().equals(StandardCharsets.UTF_8)) {
            throw new IllegalArgumentException("Режим ветвей SAP поддерживает UTF-8");
        }
        if (!Set.of("IDOBJ", "IDPERS", SELF).containsAll(options.uniqueFields())) {
            throw new IllegalArgumentException("В режиме ветвей задавайте дополнительные поля через «Связи SAP», "
                    + "а не через список уникальных полей");
        }
        addRule(new BranchReference("ET_ORG", "IDOBJ", "org"));
        addRule(new BranchReference("ET_ORG", PARENT, "org"));
        addRule(new BranchReference("ET_ORG", SELF, "org"));
        addRule(new BranchReference("ET_PERSON", "IDPERS", "person"));
        for (BranchReference rule : options.branchReferences()) {
            addRule(rule);
            personLinksConfigured |= rule.collection().equals("ET_PERSON") && rule.domain().equals("org");
        }
    }

    static SapBranchPlan analyze(Path source, ExpansionOptions options, ProgressListener listener)
            throws IOException, XMLStreamException {
        SapBranchPlan plan = new SapBranchPlan(options);
        MessageDigest digest = newDigest();
        try (InputStream file = Files.newInputStream(source);
                DigestInputStream checked = new DigestInputStream(file, digest);
                ProgressInputStream input = new ProgressInputStream(checked, Files.size(source), "INDEXING", listener)) {
            XMLStreamReader reader = XmlExpansionService.newInputFactory().createXMLStreamReader(input);
            List<String> stack = new ArrayList<>();
            try {
                while (true) {
                    if (reader.getEventType() == XMLStreamConstants.START_ELEMENT) {
                        String prefix = reader.getPrefix();
                        stack.add(prefix == null || prefix.isEmpty() ? reader.getLocalName()
                                : prefix + ":" + reader.getLocalName());
                        String path = "/" + String.join("/", stack);
                        if (XmlExpansionService.isTargetPath(path, options)) {
                            plan.record(path, XmlFragment.capture(reader));
                            stack.removeLast();
                        }
                    } else if (reader.getEventType() == XMLStreamConstants.END_ELEMENT) {
                        stack.removeLast();
                    }
                    if (!reader.hasNext()) break;
                    reader.next();
                }
            } finally {
                reader.close();
            }
        }
        plan.sourceDigest = HexFormat.of().formatHex(digest.digest());
        plan.validate();
        return plan;
    }

    private void addRule(BranchReference rule) {
        Map<String, String> fields = rules.computeIfAbsent(rule.collection(), ignored -> new LinkedHashMap<>());
        String previous = fields.putIfAbsent(rule.fieldPath(), rule.domain());
        if (previous != null && !previous.equals(rule.domain())) {
            throw new IllegalArgumentException("Противоречивые области ID для " + rule.collection() + "/" + rule.fieldPath());
        }
    }

    private void record(String path, XmlFragment fragment) {
        String collection = collection(path);
        Map<String, String> bindings = rules.get(collection);
        if (bindings == null) return;
        Set<String> selected = new LinkedHashSet<>(bindings.keySet());
        selected.add("OBJTYPE");
        List<XmlFragment.FieldValue> fields = fragment.fields(selected);
        String id = singleValue(fields, identity(collection));
        Key key = new Key(domain(collection), id);
        String type = fields.stream().filter(f -> f.path().equals("OBJTYPE"))
                .map(f -> f.value().strip()).findFirst().orElse("");
        Set<String> parents = new LinkedHashSet<>();
        List<Reference> references = new ArrayList<>();
        for (XmlFragment.FieldValue field : fields) {
            String value = field.value().strip();
            if (value.isEmpty() || !bindings.containsKey(field.path())) continue;
            if (field.path().equals(PARENT)) parents.add(value);
            if (!field.path().equals(identity(collection))) {
                references.add(new Reference(field.path(), new Key(bindings.get(field.path()), value)));
            }
            observed.add(new BranchReference(collection, field.path(), bindings.get(field.path())));
        }
        Node node = new Node(key, type, parents, references,
                !fragment.matchingExclusions(options.recordExclusions()).isEmpty());
        if (nodes.putIfAbsent(key, node) != null) {
            throw new IllegalArgumentException("Повторный исходный ID в " + collection + ": " + id
                    + ". Для копирования ветвей нужны однозначные ID записей.");
        }
    }

    private void validate() {
        if (nodes.keySet().stream().noneMatch(k -> k.domain().equals("org"))) {
            throw new IllegalArgumentException("В режиме ветвей не найдены записи ET_ORG");
        }
        for (BranchReference rule : options.branchReferences()) {
            if (!observed.contains(rule)) {
                throw new IllegalArgumentException("Не найдено непустое поле связи " + rule.collection() + "/" + rule.fieldPath());
            }
        }
        for (Node node : nodes.values()) {
            if (node.key.domain().equals("org")) {
                // Some SAP exports encode an absent parent as literal NULL. Resolve this
                // after indexing so an actual ID named NULL remains an ordinary reference.
                node.parents.removeIf(this::isAbsentParentMarker);
                node.references.removeIf(reference -> reference.field.equals(PARENT)
                        && isAbsentParentMarker(reference.target.value()));
                boolean rootShape = node.parents.isEmpty() || node.parents.equals(Set.of(node.key.value()));
                if (rootShape && node.type.equals("O")) {
                    node.preserved = true;
                    roots.add(node.key.value());
                } else if (node.preserved) {
                    roots.add(node.key.value());
                } else if (rootShape) {
                    throw new IllegalArgumentException("У записи " + node.key.value()
                            + " нет родителя, но OBJTYPE не O. Уточните структуру или явно сохраните запись без копий.");
                }
            } else {
                boolean linked = node.references.stream().anyMatch(r -> r.target.domain().equals("org"));
                node.preserved |= !personLinksConfigured || !linked;
            }
        }
        for (Node node : nodes.values()) {
            if (node.preserved) continue;
            for (Reference reference : node.references) {
                if (!nodes.containsKey(reference.target)) {
                    throw new IllegalArgumentException("Не найдена цель связи " + reference.field + "="
                            + reference.target.value() + " у записи " + node.key.value()
                            + ". Внешние корни можно явно сохранить без копий.");
                }
            }
        }
        validateParentGraph();
        Set<Key> originals = new LinkedHashSet<>(nodes.keySet());
        Set<Key> copied = new LinkedHashSet<>();
        for (Node node : nodes.values()) {
            node.references.forEach(reference -> originals.add(reference.target));
            if (!node.preserved) copied.add(node.key);
        }
        ids = new BranchIdAllocator(originals, copied);
    }

    private boolean isAbsentParentMarker(String value) {
        return value.equalsIgnoreCase("NULL") && !nodes.containsKey(new Key("org", value));
    }

    private void validateParentGraph() {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, List<String>> children = new LinkedHashMap<>();
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Node node : nodes.values()) {
            if (!node.key.domain().equals("org")) continue;
            int count = node.preserved ? 0 : node.parents.size();
            remaining.put(node.key.value(), count);
            if (count == 0) ready.add(node.key.value());
            if (!node.preserved) {
                for (String parent : node.parents) {
                    children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node.key.value());
                }
            }
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            String parent = ready.removeFirst();
            visited++;
            for (String child : children.getOrDefault(parent, List.of())) {
                if (remaining.compute(child, (key, count) -> count - 1) == 0) ready.add(child);
            }
        }
        if (visited != remaining.size()) {
            throw new IllegalArgumentException("В связях PARENT обнаружен цикл. Копирование ветвей остановлено.");
        }
    }

    boolean copies(String path, XmlFragment fragment) {
        String collection = collection(path);
        if (!rules.containsKey(collection)) return false;
        String id = singleValue(fragment.fields(Set.of(identity(collection))), identity(collection));
        Node node = nodes.get(new Key(domain(collection), id));
        if (node == null) throw new IllegalArgumentException("Исходный XML изменился после анализа");
        return !node.preserved;
    }

    XmlFragment.Rewritten rewrite(String path, XmlFragment fragment, long copy) throws XMLStreamException {
        Map<String, String> bindings = rules.get(collection(path));
        return fragment.rewrite(options.encoding(), bindings.keySet(),
                (field, value) -> ids.map(new Key(bindings.get(field), value), copy));
    }

    void checkCapacity(long copies) { ids.checkCapacity(copies); }

    void verifyOptions(ExpansionOptions actual) {
        if (!options.equals(actual)) throw new IllegalArgumentException("Параметры изменились: постройте план заново");
    }

    void verifyDigest(MessageDigest digest) {
        if (!sourceDigest.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new IllegalArgumentException("Исходный XML изменился после анализа. Постройте план заново.");
        }
    }

    public int rootCount() { return roots.size(); }
    public long copiedPersonCount() {
        return nodes.values().stream().filter(n -> n.key.domain().equals("person") && !n.preserved).count();
    }
    public long preservedPersonCount() {
        return nodes.values().stream().filter(n -> n.key.domain().equals("person") && n.preserved).count();
    }

    static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String collection(String path) {
        return path.substring(options.autoParent().length() + 1, path.lastIndexOf('/'));
    }
    private static String identity(String collection) { return collection.equals("ET_ORG") ? "IDOBJ" : "IDPERS"; }
    private static String domain(String collection) { return collection.equals("ET_ORG") ? "org" : "person"; }

    private static String singleValue(List<XmlFragment.FieldValue> fields, String field) {
        List<String> values = fields.stream().filter(f -> f.path().equals(field)).map(f -> f.value().strip()).toList();
        if (values.size() != 1 || values.getFirst().isEmpty()) {
            throw new IllegalArgumentException("Ожидалось одно непустое поле " + field + " внутри item");
        }
        return values.getFirst();
    }

    private static final class Node {
        final Key key;
        final String type;
        final Set<String> parents;
        final List<Reference> references;
        boolean preserved;
        Node(Key key, String type, Set<String> parents, List<Reference> references, boolean preserved) {
            this.key = key; this.type = type; this.parents = parents; this.references = references; this.preserved = preserved;
        }
    }
    private record Reference(String field, Key target) { }
}
