package io.github.mdsedov.xmlexpander.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class XmlExpansionServiceTest {

    private static final Path FIXTURE = Path.of("tests/fixtures/sap_sample.xml");

    private final XmlExpansionService service = new XmlExpansionService();

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversBothCollectionsAndExpandsToTargetSize() throws Exception {
        ExpansionOptions options = ExpansionOptions.targetSize(
                10L * 1024,
                List.of(),
                ExpansionOptions.DEFAULT_AUTO_PARENT,
                ExpansionOptions.DEFAULT_AUTO_ITEM,
                List.of());

        ExpansionPlan plan = service.buildPlan(FIXTURE, options, ProgressListener.NONE);
        Map<String, Long> counts = countsByPath(plan);

        assertThat(counts).containsEntry(
                "/asx:abap/asx:values/ET_ORG/item", 3L);
        assertThat(counts).containsEntry(
                "/asx:abap/asx:values/ET_PERSON/item", 3L);

        Path output = temporaryDirectory.resolve("target-size.xml");
        ExpansionResult result = service.expand(
                FIXTURE, output, options, plan, false, ProgressListener.NONE);

        assertThat(result.outputBytes()).isGreaterThanOrEqualTo(10L * 1024);
        assertThat(result.outputBytes()).isLessThanOrEqualTo(
                plan.estimatedOutputBytes() + plan.maximumResidualOvershootBytes());
        assertThat(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile()))
                .isNotNull();

        ExpansionPlan expandedPlan = service.buildPlan(
                output,
                ExpansionOptions.targetSize(
                        1,
                        List.of(),
                        ExpansionOptions.DEFAULT_AUTO_PARENT,
                        ExpansionOptions.DEFAULT_AUTO_ITEM,
                        List.of()),
                ProgressListener.NONE);
        assertThat(countsByPath(expandedPlan).get(
                "/asx:abap/asx:values/ET_ORG/item")).isGreaterThan(3);
        assertThat(countsByPath(expandedPlan).get(
                "/asx:abap/asx:values/ET_PERSON/item")).isGreaterThan(3);
    }

    @Test
    void fixedCopiesMutateOnlyDuplicateUniqueFieldsAndKeepSourceUntouched() throws Exception {
        byte[] sourceDigest = sha256(FIXTURE);
        ExpansionOptions options = ExpansionOptions.fixedCopies(
                2,
                List.of(),
                ExpansionOptions.DEFAULT_AUTO_PARENT,
                ExpansionOptions.DEFAULT_AUTO_ITEM,
                List.of("IDOBJ"));
        ExpansionPlan plan = service.buildPlan(FIXTURE, options, ProgressListener.NONE);
        Path output = temporaryDirectory.resolve("fixed-copies.xml");

        ExpansionResult result = service.expand(
                FIXTURE, output, options, plan, false, ProgressListener.NONE);

        assertThat(result.duplicatesWritten()).isEqualTo(12);
        assertThat(result.mutatedFields()).isEqualTo(6);
        assertThat(sha256(FIXTURE)).isEqualTo(sourceDigest);

        ExpansionPlan expandedPlan = service.buildPlan(
                output,
                ExpansionOptions.targetSize(
                        1,
                        List.of(),
                        ExpansionOptions.DEFAULT_AUTO_PARENT,
                        ExpansionOptions.DEFAULT_AUTO_ITEM,
                        List.of()),
                ProgressListener.NONE);
        assertThat(countsByPath(expandedPlan)).containsEntry(
                "/asx:abap/asx:values/ET_ORG/item", 9L);
        assertThat(countsByPath(expandedPlan)).containsEntry(
                "/asx:abap/asx:values/ET_PERSON/item", 9L);

        NodeList idNodes = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(output.toFile())
                .getElementsByTagName("IDOBJ");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < idNodes.getLength(); index++) {
            ids.add(idNodes.item(index).getTextContent());
        }
        assertThat(ids).hasSize(idNodes.getLength());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2})
    void expandsCompactXmlWithFixedCopies(long extraCopies) throws Exception {
        Path source = compactFixture();
        ExpansionOptions options = ExpansionOptions.fixedCopies(
                extraCopies, List.of(), null, null, List.of("IDOBJ"));
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = temporaryDirectory.resolve("compact-fixed.xml");

        ExpansionResult result = service.expand(
                source, output, options, plan, false, ProgressListener.NONE);

        Document document = parseXml(output);
        assertCollectionSize(document, "ET_ORG", 3 * (extraCopies + 1));
        assertCollectionSize(document, "ET_PERSON", 3 * (extraCopies + 1));
        assertThat(result.duplicatesWritten()).isEqualTo(6 * extraCopies);
        assertThat(result.mutatedFields()).isEqualTo(3 * extraCopies);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        assertThat(plan.originalSerializedBytes()).isEqualTo(Files.size(source));
    }

    @Test
    void expandsCompactXmlToTargetSize() throws Exception {
        Path source = compactFixture();
        ExpansionOptions options = ExpansionOptions.targetSize(
                10L * 1024, List.of(), null, null, List.of());
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = temporaryDirectory.resolve("compact-target.xml");

        ExpansionResult result = service.expand(
                source, output, options, plan, false, ProgressListener.NONE);

        Document document = parseXml(output);
        assertThat(result.outputBytes()).isBetween(
                plan.requestedTargetBytes(),
                plan.estimatedOutputBytes() + plan.maximumResidualOvershootBytes());
        ExpansionPlan expandedPlan = service.buildPlan(output, options, ProgressListener.NONE);
        for (TargetPathPlan path : expandedPlan.targetPaths()) {
            String collection = path.path().split("/")[3];
            assertThat(path.recordCount()).isGreaterThan(3);
            assertCollectionSize(document, collection, path.recordCount());
        }
    }

    @Test
    void expandsCompactExplicitPathWithAttributesAndInheritedNamespace() throws Exception {
        Path source = temporaryDirectory.resolve("namespaced.xml");
        Files.writeString(source, """
                <root xmlns:r="urn:test"><r:records label="A &amp; B"><r:item r:id="1"><r:value/></r:item></r:records><footer/></root>""");
        ExpansionOptions options = ExpansionOptions.fixedCopies(
                1, List.of("/root/r:records/r:item"), null, null, List.of());
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = temporaryDirectory.resolve("namespaced-expanded.xml");

        ExpansionResult result = service.expand(
                source, output, options, plan, false, ProgressListener.NONE);

        Document document = parseXml(output);
        Element collection = (Element) document.getElementsByTagNameNS("urn:test", "records").item(0);
        assertThat(collection.getAttribute("label")).isEqualTo("A & B");
        assertThat(collection.getTextContent()).isEmpty();
        NodeList items = collection.getElementsByTagNameNS("urn:test", "item");
        assertThat(items.getLength()).isEqualTo(2);
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            assertThat(item.getAttributeNS("urn:test", "id")).isEqualTo("1");
            assertThat(item.getElementsByTagNameNS("urn:test", "value").getLength()).isEqualTo(1);
        }
        assertThat(document.getElementsByTagName("footer").getLength()).isEqualTo(1);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
    }

    @Test
    void refusesToOverwriteAnExistingOutputWithoutExplicitPermission() throws Exception {
        ExpansionOptions options = ExpansionOptions.fixedCopies(
                1,
                List.of(),
                ExpansionOptions.DEFAULT_AUTO_PARENT,
                ExpansionOptions.DEFAULT_AUTO_ITEM,
                List.of());
        ExpansionPlan plan = service.buildPlan(FIXTURE, options, ProgressListener.NONE);
        Path output = temporaryDirectory.resolve("existing.xml");
        Files.writeString(output, "do-not-replace");

        assertThatThrownBy(() -> service.expand(
                FIXTURE, output, options, plan, false, ProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("уже существует");
        assertThat(Files.readString(output)).isEqualTo("do-not-replace");
    }

    private Path compactFixture() throws Exception {
        Path source = temporaryDirectory.resolve("compact.xml");
        Files.writeString(source, Files.readString(FIXTURE).replaceAll(">\\s+<", "><").strip());
        return source;
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static void assertCollectionSize(Document document, String name, long expected) {
        Element collection = (Element) document.getElementsByTagName(name).item(0);
        NodeList children = collection.getChildNodes();
        assertThat(children.getLength()).isEqualTo((int) expected);
        for (int index = 0; index < children.getLength(); index++) {
            assertThat(children.item(index).getNodeName()).isEqualTo("item");
        }
    }

    private static Map<String, Long> countsByPath(ExpansionPlan plan) {
        return plan.targetPaths().stream().collect(Collectors.toMap(
                TargetPathPlan::path,
                TargetPathPlan::recordCount));
    }

    private static byte[] sha256(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
