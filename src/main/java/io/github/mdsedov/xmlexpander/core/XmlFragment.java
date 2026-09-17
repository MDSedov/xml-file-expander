package io.github.mdsedov.xmlexpander.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

final class XmlFragment {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*?)(\\d+)$", Pattern.DOTALL);
    private static final BigInteger NUMBER_STEP = BigInteger.valueOf(104_729L);

    private final List<Token> tokens;

    private XmlFragment(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    static XmlFragment capture(XMLStreamReader reader) throws XMLStreamException {
        if (reader.getEventType() != XMLStreamConstants.START_ELEMENT) {
            throw new IllegalArgumentException("XML reader must point to START_ELEMENT");
        }

        List<Token> tokens = new ArrayList<>();
        List<String> elementStack = new ArrayList<>();
        int depth = 0;

        while (true) {
            switch (reader.getEventType()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    elementStack.add(qName(reader.getPrefix(), reader.getLocalName()));
                    depth++;
                    tokens.add(readStart(reader));
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    tokens.add(new EndToken());
                    elementStack.removeLast();
                    depth--;
                    if (depth == 0) {
                        return new XmlFragment(tokens);
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE,
                        XMLStreamConstants.ENTITY_REFERENCE -> addText(
                                tokens,
                                reader.getText(),
                                relativePath(elementStack),
                                false);
                case XMLStreamConstants.CDATA -> addText(
                        tokens,
                        reader.getText(),
                        relativePath(elementStack),
                        true);
                case XMLStreamConstants.COMMENT -> tokens.add(new CommentToken(reader.getText()));
                case XMLStreamConstants.PROCESSING_INSTRUCTION -> tokens.add(
                        new ProcessingInstructionToken(reader.getPITarget(), reader.getPIData()));
                default -> {
                    // DTD and external entities are disabled by the caller.
                }
            }
            reader.next();
        }
    }

    byte[] toBytes(Charset encoding, Set<String> uniqueFields, long serial) throws XMLStreamException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        XMLStreamWriter writer = XMLOutputFactory.newFactory()
                .createXMLStreamWriter(output, encoding.name());
        boolean mutate = serial > 0 && uniqueFields != null && !uniqueFields.isEmpty();

        for (Token token : tokens) {
            switch (token) {
                case StartToken start -> writeStart(writer, start);
                case EndToken ignored -> writer.writeEndElement();
                case TextToken text -> {
                    String value = mutate && uniqueFields.contains(text.relativePath())
                            ? mutatePreservingWhitespace(text.value(), serial)
                            : text.value();
                    if (text.cdata()) {
                        writer.writeCData(value);
                    } else {
                        writer.writeCharacters(value);
                    }
                }
                case CommentToken comment -> writer.writeComment(comment.value());
                case ProcessingInstructionToken instruction -> {
                    if (instruction.data() == null || instruction.data().isBlank()) {
                        writer.writeProcessingInstruction(instruction.target());
                    } else {
                        writer.writeProcessingInstruction(instruction.target(), instruction.data());
                    }
                }
            }
        }
        writer.flush();
        writer.close();
        return output.toByteArray();
    }

    long matchingTextFieldCount(Set<String> uniqueFields) {
        if (uniqueFields == null || uniqueFields.isEmpty()) {
            return 0;
        }
        return tokens.stream()
                .filter(TextToken.class::isInstance)
                .map(TextToken.class::cast)
                .filter(token -> !token.value().isBlank())
                .filter(token -> uniqueFields.contains(token.relativePath()))
                .count();
    }

    Set<RecordExclusion> matchingExclusions(List<RecordExclusion> exclusions) {
        if (exclusions.isEmpty()) {
            return Set.of();
        }
        Set<RecordExclusion> matches = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>();
        List<StringBuilder> values = new ArrayList<>();
        for (Token token : tokens) {
            switch (token) {
                case StartToken start -> {
                    stack.add(qName(start.prefix(), start.localName()));
                    String path = relativePath(stack);
                    boolean selected = exclusions.stream().anyMatch(rule -> rule.fieldPath().equals(path));
                    values.add(selected ? new StringBuilder() : null);
                }
                case TextToken text -> {
                    StringBuilder fieldValue = values.getLast();
                    if (fieldValue != null) {
                        fieldValue.append(text.value());
                    }
                }
                case EndToken ignored -> {
                    StringBuilder fieldValue = values.removeLast();
                    if (fieldValue != null) {
                        String fieldPath = relativePath(stack);
                        String value = fieldValue.toString().strip();
                        for (RecordExclusion rule : exclusions) {
                            if (rule.fieldPath().equals(fieldPath) && rule.value().equals(value)) {
                                matches.add(rule);
                            }
                        }
                    }
                    stack.removeLast();
                }
                default -> {
                    // Comments and processing instructions may split a field's text.
                }
            }
        }
        return matches;
    }

    List<FieldValue> fields(Set<String> selected) {
        List<FieldValue> result = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        List<List<Integer>> indices = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            switch (tokens.get(index)) {
                case StartToken start -> {
                    if (!indices.isEmpty() && indices.getLast() != null) {
                        throw new IllegalArgumentException("Поле связи должно быть текстовым: " + relativePath(stack));
                    }
                    stack.add(qName(start.prefix(), start.localName()));
                    indices.add(selected.contains(relativePath(stack)) ? new ArrayList<>() : null);
                }
                case TextToken ignored -> {
                    if (indices.getLast() != null) {
                        indices.getLast().add(index);
                    }
                }
                case EndToken ignored -> {
                    List<Integer> fieldIndices = indices.removeLast();
                    if (fieldIndices != null) {
                        StringBuilder value = new StringBuilder();
                        fieldIndices.forEach(i -> value.append(((TextToken) tokens.get(i)).value()));
                        result.add(new FieldValue(relativePath(stack), value.toString(), fieldIndices));
                    }
                    stack.removeLast();
                }
                default -> { }
            }
        }
        return result;
    }

    Rewritten rewrite(Charset encoding, Set<String> selected,
            BiFunction<String, String, String> mapper) throws XMLStreamException {
        List<Token> changed = new ArrayList<>(tokens);
        long changes = 0;
        for (FieldValue field : fields(selected)) {
            String trimmed = field.value().strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String replacement = mapper.apply(field.path(), trimmed);
            if (!replacement.equals(trimmed)) {
                changes++;
            }
            int start = 0;
            int end = field.value().length();
            while (start < end && Character.isWhitespace(field.value().charAt(start))) start++;
            while (end > start && Character.isWhitespace(field.value().charAt(end - 1))) end--;
            String value = field.value().substring(0, start) + replacement + field.value().substring(end);
            // Replace a logical field once even when comments or CDATA split its text events.
            for (int i = 0; i < field.tokenIndices().size(); i++) {
                changed.set(field.tokenIndices().get(i), new TextToken(i == 0 ? value : "", field.path(), false));
            }
        }
        return new Rewritten(new XmlFragment(changed).toBytes(encoding, Set.of(), 0), changes);
    }

    record FieldValue(String path, String value, List<Integer> tokenIndices) { }
    record Rewritten(byte[] bytes, long changedFields) { }

    private static StartToken readStart(XMLStreamReader reader) {
        List<NamespaceToken> namespaces = new ArrayList<>();
        for (int index = 0; index < reader.getNamespaceCount(); index++) {
            namespaces.add(new NamespaceToken(
                    nullToEmpty(reader.getNamespacePrefix(index)),
                    nullToEmpty(reader.getNamespaceURI(index))));
        }

        List<AttributeToken> attributes = new ArrayList<>();
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            attributes.add(new AttributeToken(
                    nullToEmpty(reader.getAttributePrefix(index)),
                    reader.getAttributeLocalName(index),
                    nullToEmpty(reader.getAttributeNamespace(index)),
                    reader.getAttributeValue(index)));
        }

        return new StartToken(
                nullToEmpty(reader.getPrefix()),
                reader.getLocalName(),
                nullToEmpty(reader.getNamespaceURI()),
                List.copyOf(namespaces),
                List.copyOf(attributes));
    }

    private static void writeStart(XMLStreamWriter writer, StartToken start) throws XMLStreamException {
        if (start.namespaceUri().isEmpty() && start.prefix().isEmpty()) {
            writer.writeStartElement(start.localName());
        } else {
            writer.writeStartElement(start.prefix(), start.localName(), start.namespaceUri());
        }

        for (NamespaceToken namespace : start.namespaces()) {
            if (namespace.prefix().isEmpty()) {
                writer.writeDefaultNamespace(namespace.uri());
            } else {
                writer.writeNamespace(namespace.prefix(), namespace.uri());
            }
        }

        for (AttributeToken attribute : start.attributes()) {
            if (attribute.namespaceUri().isEmpty() && attribute.prefix().isEmpty()) {
                writer.writeAttribute(attribute.localName(), attribute.value());
            } else {
                writer.writeAttribute(
                        attribute.prefix(),
                        attribute.namespaceUri(),
                        attribute.localName(),
                        attribute.value());
            }
        }
    }

    private static void addText(
            List<Token> tokens,
            String value,
            String relativePath,
            boolean cdata) {
        if (!tokens.isEmpty() && tokens.getLast() instanceof TextToken previous
                && previous.cdata() == cdata
                && previous.relativePath().equals(relativePath)) {
            tokens.set(tokens.size() - 1, new TextToken(
                    previous.value() + value,
                    relativePath,
                    cdata));
            return;
        }
        tokens.add(new TextToken(value, relativePath, cdata));
    }

    private static String mutatePreservingWhitespace(String value, long serial) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, start)
                + mutateValue(value.substring(start, end), serial)
                + value.substring(end);
    }

    private static String mutateValue(String value, long serial) {
        String suffix = "DUP%07d".formatted(serial);

        int atIndex = value.indexOf('@');
        if (atIndex > 0 && atIndex < value.length() - 1 && !value.contains(" ")) {
            return value.substring(0, atIndex)
                    + ".dup%07d".formatted(serial)
                    + value.substring(atIndex);
        }

        Matcher numberMatcher = TRAILING_NUMBER.matcher(value);
        if (numberMatcher.matches()) {
            String prefix = numberMatcher.group(1);
            String digits = numberMatcher.group(2);
            BigInteger modulus = BigInteger.TEN.pow(digits.length());
            BigInteger changed = new BigInteger(digits)
                    .add(NUMBER_STEP.multiply(BigInteger.valueOf(serial)))
                    .mod(modulus);
            return prefix + String.format("%0" + digits.length() + "d", changed);
        }

        return value.isEmpty() ? suffix : value + "_" + suffix;
    }

    private static String relativePath(List<String> elementStack) {
        if (elementStack.size() <= 1) {
            return "";
        }
        return String.join("/", elementStack.subList(1, elementStack.size()));
    }

    private static String qName(String prefix, String localName) {
        return prefix == null || prefix.isBlank() ? localName : prefix + ":" + localName;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private sealed interface Token permits StartToken, EndToken, TextToken,
            CommentToken, ProcessingInstructionToken {
    }

    private record StartToken(
            String prefix,
            String localName,
            String namespaceUri,
            List<NamespaceToken> namespaces,
            List<AttributeToken> attributes) implements Token {
    }

    private record EndToken() implements Token {
    }

    private record TextToken(String value, String relativePath, boolean cdata) implements Token {
    }

    private record CommentToken(String value) implements Token {
    }

    private record ProcessingInstructionToken(String target, String data) implements Token {
    }

    private record NamespaceToken(String prefix, String uri) {
    }

    private record AttributeToken(
            String prefix,
            String localName,
            String namespaceUri,
            String value) {
    }
}
