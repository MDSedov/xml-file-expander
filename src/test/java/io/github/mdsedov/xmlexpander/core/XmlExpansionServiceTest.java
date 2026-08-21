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

    private static Map<String, Long> countsByPath(ExpansionPlan plan) {
        return plan.targetPaths().stream().collect(Collectors.toMap(
                TargetPathPlan::path,
                TargetPathPlan::recordCount));
    }

    private static byte[] sha256(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
