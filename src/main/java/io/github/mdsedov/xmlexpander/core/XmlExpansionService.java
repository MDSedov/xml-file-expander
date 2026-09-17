package io.github.mdsedov.xmlexpander.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.springframework.stereotype.Service;

@Service
public class XmlExpansionService {

    private static final long OUTPUT_PROGRESS_STEP = 8L * 1024 * 1024;

    public ExpansionPlan buildPlan(
            Path inputXml,
            ExpansionOptions options,
            ProgressListener progressListener) throws IOException, XMLStreamException {
        validateInput(inputXml);
        ProgressListener listener = progressListener == null ? ProgressListener.NONE : progressListener;
        long inputSize = Files.size(inputXml);
        LinkedHashMap<String, PathAccumulator> accumulators = new LinkedHashMap<>();
        options.targetPaths().forEach(path -> accumulators.put(path, new PathAccumulator()));

        CountingOutputStream output = new CountingOutputStream(OutputStream.nullOutputStream());
        XMLStreamWriter writer = newOutputFactory()
                .createXMLStreamWriter(output, options.encoding().name());
        XMLStreamReader reader = null;

        try (InputStream fileInput = Files.newInputStream(inputXml);
                ProgressInputStream input = new ProgressInputStream(
                        fileInput, inputSize, "PLANNING", listener)) {
            reader = newInputFactory().createXMLStreamReader(input);
            List<String> pathStack = new ArrayList<>();

            while (true) {
                int event = reader.getEventType();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    pathStack.add(qName(reader.getPrefix(), reader.getLocalName()));
                    String path = currentPath(pathStack);
                    if (isTargetPath(path, options)) {
                        XmlFragment fragment = XmlFragment.capture(reader);
                        byte[] bytes = fragment.toBytes(options.encoding(), Set.of(), 0);
                        writeFragment(writer, output, bytes);

                        PathAccumulator accumulator = accumulators.computeIfAbsent(
                                path, ignored -> new PathAccumulator());
                        accumulator.recordCount++;
                        accumulator.recordBytes += bytes.length;
                        accumulator.maxRecordBytes = Math.max(accumulator.maxRecordBytes, bytes.length);
                        pathStack.removeLast();
                    } else {
                        writeStartElement(reader, writer);
                    }
                } else {
                    writeNonStartEvent(reader, writer, pathStack);
                }

                if (!reader.hasNext()) {
                    break;
                }
                reader.next();
            }
            writer.flush();
        } finally {
            if (reader != null) {
                reader.close();
            }
            writer.close();
        }

        List<String> missingPaths = accumulators.entrySet().stream()
                .filter(entry -> entry.getValue().recordCount == 0)
                .map(Map.Entry::getKey)
                .toList();
        if (!missingPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "В XML не найдены указанные пути записей: " + String.join(", ", missingPaths));
        }

        long repeatableBytes = accumulators.values().stream()
                .mapToLong(value -> value.recordBytes)
                .sum();
        if (repeatableBytes <= 0) {
            throw new IllegalArgumentException(
                    "Коллекции для расширения не найдены. Ожидались пути вида "
                            + options.autoParent() + "/*/" + options.autoItem());
        }

        long originalBytes = output.count();
        long fullCopies;
        long residualBytes;
        long estimatedOutput;
        long requestedTarget;

        if (options.fixedCopiesMode()) {
            fullCopies = options.fixedExtraCopies();
            residualBytes = 0;
            estimatedOutput = Math.addExact(
                    originalBytes,
                    Math.multiplyExact(repeatableBytes, fullCopies));
            requestedTarget = estimatedOutput;
        } else {
            requestedTarget = options.targetSizeBytes();
            long extraNeeded = Math.max(0, requestedTarget - originalBytes);
            fullCopies = extraNeeded / repeatableBytes;
            residualBytes = extraNeeded % repeatableBytes;
            estimatedOutput = Math.addExact(
                    originalBytes,
                    Math.addExact(
                            Math.multiplyExact(repeatableBytes, fullCopies),
                            residualBytes));
        }

        Map<String, Long> residualByPath = distributeResidual(residualBytes, accumulators);
        List<TargetPathPlan> pathPlans = accumulators.entrySet().stream()
                .map(entry -> new TargetPathPlan(
                        entry.getKey(),
                        entry.getValue().recordCount,
                        entry.getValue().recordBytes,
                        entry.getValue().maxRecordBytes,
                        residualByPath.getOrDefault(entry.getKey(), 0L)))
                .toList();

        listener.onProgress(new ProgressUpdate(
                "READY", inputSize, inputSize, "План расширения построен"));
        return new ExpansionPlan(
                originalBytes,
                requestedTarget,
                repeatableBytes,
                fullCopies,
                residualBytes,
                estimatedOutput,
                options.fixedCopiesMode(),
                pathPlans);
    }

    public ExpansionResult expand(
            Path inputXml,
            Path requestedOutput,
            ExpansionOptions options,
            ExpansionPlan plan,
            boolean overwrite,
            ProgressListener progressListener) throws IOException, XMLStreamException {
        validateInput(inputXml);
        ProgressListener listener = progressListener == null ? ProgressListener.NONE : progressListener;
        Path outputXml = requestedOutput.toAbsolutePath().normalize();
        Path inputAbsolute = inputXml.toAbsolutePath().normalize();
        if (inputAbsolute.equals(outputXml)
                || (Files.exists(outputXml) && Files.isSameFile(inputAbsolute, outputXml))) {
            throw new IllegalArgumentException("Исходный и выходной XML должны быть разными файлами");
        }
        if (Files.exists(outputXml) && !overwrite) {
            throw new IllegalArgumentException(
                    "Выходной файл уже существует. Включите разрешение на перезапись: " + outputXml);
        }

        Path outputParent = outputXml.getParent();
        if (outputParent == null) {
            throw new IllegalArgumentException("Не удалось определить каталог выходного файла");
        }
        Files.createDirectories(outputParent);
        FileStore fileStore = Files.getFileStore(outputParent);
        long upperEstimate = Math.addExact(
                plan.estimatedOutputBytes(), plan.maximumResidualOvershootBytes());
        if (fileStore.getUsableSpace() < upperEstimate) {
            throw new IllegalArgumentException(
                    "Недостаточно свободного места: требуется около " + upperEstimate
                            + " байт, доступно " + fileStore.getUsableSpace() + " байт");
        }

        Path temporaryOutput = Files.createTempFile(outputParent, ".expanded-xml-", ".tmp");
        MutableExpansionStats stats = new MutableExpansionStats();
        Map<String, Long> residualBudgets = plan.targetPaths().stream().collect(Collectors.toMap(
                TargetPathPlan::path,
                TargetPathPlan::residualExtraBytes,
                (left, right) -> left,
                LinkedHashMap::new));
        Map<String, Long> residualWritten = new LinkedHashMap<>();
        Set<String> targetPaths = plan.targetPaths().stream()
                .map(TargetPathPlan::path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> uniqueFields = new LinkedHashSet<>(options.uniqueFields());
        long inputSize = Files.size(inputXml);

        try {
            try (InputStream fileInput = Files.newInputStream(inputXml);
                    ProgressInputStream input = new ProgressInputStream(
                            fileInput, inputSize, "READING", listener);
                    CountingOutputStream output = new CountingOutputStream(
                            Files.newOutputStream(temporaryOutput))) {
                XMLStreamReader reader = newInputFactory().createXMLStreamReader(input);
                XMLStreamWriter writer = newOutputFactory()
                        .createXMLStreamWriter(output, options.encoding().name());
                List<String> pathStack = new ArrayList<>();
                long nextOutputNotification = OUTPUT_PROGRESS_STEP;

                try {
                    while (true) {
                        int event = reader.getEventType();
                        if (event == XMLStreamConstants.START_ELEMENT) {
                            pathStack.add(qName(reader.getPrefix(), reader.getLocalName()));
                            String path = currentPath(pathStack);
                            if (targetPaths.contains(path)) {
                                XmlFragment fragment = XmlFragment.capture(reader);
                                byte[] originalFragment = fragment.toBytes(
                                        options.encoding(), Set.of(), 0);
                                writeFragment(writer, output, originalFragment);

                                for (long copy = 0; copy < plan.fullExtraCopiesPerRecord(); copy++) {
                                    writeDuplicate(
                                            fragment,
                                            originalFragment,
                                            options,
                                            uniqueFields,
                                            output,
                                            stats);
                                }

                                long residualLimit = residualBudgets.getOrDefault(path, 0L);
                                long alreadyWritten = residualWritten.getOrDefault(path, 0L);
                                if (alreadyWritten < residualLimit) {
                                    writeDuplicate(
                                            fragment,
                                            originalFragment,
                                            options,
                                            uniqueFields,
                                            output,
                                            stats);
                                    residualWritten.put(path, alreadyWritten + originalFragment.length);
                                }
                                pathStack.removeLast();
                            } else {
                                writeStartElement(reader, writer);
                            }
                        } else {
                            writeNonStartEvent(reader, writer, pathStack);
                        }

                        if (output.count() >= nextOutputNotification) {
                            listener.onProgress(new ProgressUpdate(
                                    "WRITING",
                                    output.count(),
                                    upperEstimate,
                                    "Запись расширенного XML"));
                            nextOutputNotification = output.count() + OUTPUT_PROGRESS_STEP;
                        }

                        if (!reader.hasNext()) {
                            break;
                        }
                        reader.next();
                    }
                    writer.flush();
                    stats.outputBytes = output.count();
                } finally {
                    writer.close();
                    reader.close();
                }
            }

            moveCompletedFile(temporaryOutput, outputXml, overwrite);
        } catch (IOException | XMLStreamException | RuntimeException exception) {
            Files.deleteIfExists(temporaryOutput);
            throw exception;
        }

        listener.onProgress(new ProgressUpdate(
                "COMPLETED",
                stats.outputBytes,
                stats.outputBytes,
                "Расширение завершено"));
        return new ExpansionResult(
                outputXml,
                stats.outputBytes,
                stats.duplicatesWritten,
                stats.mutatedFields);
    }

    private static void writeFragment(
            XMLStreamWriter writer,
            OutputStream output,
            byte[] fragment) throws XMLStreamException, IOException {
        // flush() alone can leave a pending start tag open (e.g. "<ET_ORG").
        // Empty character content closes it without adding whitespace to the XML.
        writer.writeCharacters("");
        writer.flush();
        output.write(fragment);
    }

    private static void writeDuplicate(
            XmlFragment fragment,
            byte[] originalFragment,
            ExpansionOptions options,
            Set<String> uniqueFields,
            OutputStream output,
            MutableExpansionStats stats) throws IOException, XMLStreamException {
        stats.duplicateSerial++;
        if (uniqueFields.isEmpty()) {
            output.write(originalFragment);
        } else {
            output.write(fragment.toBytes(options.encoding(), uniqueFields, stats.duplicateSerial));
            stats.mutatedFields += fragment.matchingTextFieldCount(uniqueFields);
        }
        stats.duplicatesWritten++;
    }

    private static void moveCompletedFile(Path temporary, Path output, boolean overwrite)
            throws IOException {
        try {
            if (overwrite) {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (overwrite) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, output);
            }
        }
    }

    private static Map<String, Long> distributeResidual(
            long residualBytes,
            LinkedHashMap<String, PathAccumulator> accumulators) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        accumulators.keySet().forEach(path -> result.put(path, 0L));
        if (residualBytes <= 0) {
            return result;
        }

        long totalBytes = accumulators.values().stream()
                .mapToLong(value -> value.recordBytes)
                .sum();
        if (totalBytes <= 0) {
            return result;
        }

        BigInteger total = BigInteger.valueOf(totalBytes);
        List<ResidualRemainder> remainders = new ArrayList<>();
        long allocated = 0;
        for (Map.Entry<String, PathAccumulator> entry : accumulators.entrySet()) {
            BigInteger product = BigInteger.valueOf(residualBytes)
                    .multiply(BigInteger.valueOf(entry.getValue().recordBytes));
            BigInteger[] division = product.divideAndRemainder(total);
            long whole = division[0].longValueExact();
            result.put(entry.getKey(), whole);
            allocated += whole;
            remainders.add(new ResidualRemainder(entry.getKey(), division[1]));
        }

        long remaining = residualBytes - allocated;
        remainders.sort(Comparator.comparing(ResidualRemainder::remainder).reversed());
        for (int index = 0; index < remaining; index++) {
            String path = remainders.get(index).path();
            result.put(path, result.get(path) + 1);
        }
        return result;
    }

    private static void writeStartElement(XMLStreamReader reader, XMLStreamWriter writer)
            throws XMLStreamException {
        String prefix = nullToEmpty(reader.getPrefix());
        String namespace = nullToEmpty(reader.getNamespaceURI());
        if (prefix.isEmpty() && namespace.isEmpty()) {
            writer.writeStartElement(reader.getLocalName());
        } else {
            writer.writeStartElement(prefix, reader.getLocalName(), namespace);
        }

        for (int index = 0; index < reader.getNamespaceCount(); index++) {
            String namespacePrefix = nullToEmpty(reader.getNamespacePrefix(index));
            String namespaceUri = nullToEmpty(reader.getNamespaceURI(index));
            if (namespacePrefix.isEmpty()) {
                writer.writeDefaultNamespace(namespaceUri);
            } else {
                writer.writeNamespace(namespacePrefix, namespaceUri);
            }
        }

        for (int index = 0; index < reader.getAttributeCount(); index++) {
            String attributePrefix = nullToEmpty(reader.getAttributePrefix(index));
            String attributeNamespace = nullToEmpty(reader.getAttributeNamespace(index));
            if (attributePrefix.isEmpty() && attributeNamespace.isEmpty()) {
                writer.writeAttribute(reader.getAttributeLocalName(index), reader.getAttributeValue(index));
            } else {
                writer.writeAttribute(
                        attributePrefix,
                        attributeNamespace,
                        reader.getAttributeLocalName(index),
                        reader.getAttributeValue(index));
            }
        }
    }

    private static void writeNonStartEvent(
            XMLStreamReader reader,
            XMLStreamWriter writer,
            List<String> pathStack) throws XMLStreamException {
        switch (reader.getEventType()) {
            case XMLStreamConstants.START_DOCUMENT -> writer.writeStartDocument(
                    "UTF-8",
                    reader.getVersion() == null ? "1.0" : reader.getVersion());
            case XMLStreamConstants.END_DOCUMENT -> writer.writeEndDocument();
            case XMLStreamConstants.END_ELEMENT -> {
                writer.writeEndElement();
                pathStack.removeLast();
            }
            case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE ->
                    writer.writeCharacters(reader.getText());
            case XMLStreamConstants.CDATA -> writer.writeCData(reader.getText());
            case XMLStreamConstants.COMMENT -> writer.writeComment(reader.getText());
            case XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                if (reader.getPIData() == null || reader.getPIData().isBlank()) {
                    writer.writeProcessingInstruction(reader.getPITarget());
                } else {
                    writer.writeProcessingInstruction(reader.getPITarget(), reader.getPIData());
                }
            }
            case XMLStreamConstants.ENTITY_REFERENCE -> writer.writeCharacters(reader.getText());
            default -> {
                // DTD and external entities are intentionally not copied.
            }
        }
    }

    private static boolean isTargetPath(String path, ExpansionOptions options) {
        if (!options.autoDiscovery()) {
            return options.targetPaths().contains(path);
        }

        List<String> current = splitPath(path);
        List<String> parent = splitPath(options.autoParent());
        return current.size() == parent.size() + 2
                && current.subList(0, parent.size()).equals(parent)
                && current.getLast().equals(options.autoItem());
    }

    private static List<String> splitPath(String path) {
        return List.of(path.substring(1).split("/"));
    }

    private static String currentPath(List<String> pathStack) {
        return "/" + String.join("/", pathStack);
    }

    private static String qName(String prefix, String localName) {
        return prefix == null || prefix.isBlank() ? localName : prefix + ":" + localName;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static XMLInputFactory newInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setInputProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setInputProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setInputProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        setInputProperty(factory, XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }

    private static XMLOutputFactory newOutputFactory() {
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        try {
            factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, false);
        } catch (IllegalArgumentException ignored) {
            // The JDK implementation supports it; this keeps alternative providers usable.
        }
        return factory;
    }

    private static void setInputProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // Unsupported optional properties are ignored; external resolvers remain unset.
        }
    }

    private static void validateInput(Path inputXml) {
        if (inputXml == null || !Files.isRegularFile(inputXml) || !Files.isReadable(inputXml)) {
            throw new IllegalArgumentException("Исходный XML не найден или недоступен для чтения");
        }
    }

    private static final class PathAccumulator {
        private long recordCount;
        private long recordBytes;
        private long maxRecordBytes;
    }

    private static final class MutableExpansionStats {
        private long outputBytes;
        private long duplicatesWritten;
        private long mutatedFields;
        private long duplicateSerial;
    }

    private record ResidualRemainder(String path, BigInteger remainder) {
    }
}
