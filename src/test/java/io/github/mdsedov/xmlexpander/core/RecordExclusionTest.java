package io.github.mdsedov.xmlexpander.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class RecordExclusionTest {

    private static final List<String> UNIQUE_FIELDS =
            List.of("IDOBJ", "DYN_ATTR/HRP9110/item/OBJID", "IDPERS");

    private final XmlExpansionService service = new XmlExpansionService();

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "<PARENT/>",
            "<PARENT><item><VALUE>10000001</VALUE></item></PARENT>",
            "<PARENT><item><VALUE>00000000</VALUE></item></PARENT>"
    })
    void preservesRootWithoutAssumingHowUploaderRecognizesIt(String rootParent) throws Exception {
        Path source = writeTree(rootParent);
        String original = Files.readString(source);
        ExpansionOptions options = fixedCopies(15).withRecordExclusions(
                List.of(RecordExclusion.parse("IDOBJ=10000001")));
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("expanded.xml");

        ExpansionResult result = service.expand(
                source, output, options, plan, false, ProgressListener.NONE);

        assertThat(plan.targetPaths().getFirst().recordCount()).isEqualTo(3);
        assertThat(plan.targetPaths().getFirst().excludedRecordCount()).isEqualTo(1);
        assertThat(result.duplicatesWritten()).isEqualTo(45);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        Document expanded = parse(output);
        assertConnectedTreeWithUniqueIds(expanded, 33);
        assertThat(expanded.getElementsByTagName("IDPERS").getLength()).isEqualTo(16);
        assertThat(Files.readString(source)).isEqualTo(original);
    }

    @Test
    void targetSizeAndResidualCopiesExcludeRootBytes() throws Exception {
        Path source = writeTree("");
        ExpansionOptions baseOptions = fixedCopies(0).withRecordExclusions(
                List.of(RecordExclusion.parse("IDOBJ=10000001")));
        ExpansionPlan baseline = service.buildPlan(source, baseOptions, ProgressListener.NONE);
        long target = baseline.originalSerializedBytes() + baseline.repeatableRecordBytes()
                + baseline.repeatableRecordBytes() / 2;
        ExpansionOptions options = ExpansionOptions.targetSize(target, List.of(), null, null, UNIQUE_FIELDS)
                .withRecordExclusions(baseOptions.recordExclusions());
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("target.xml");

        ExpansionResult result = service.expand(source, output, options, plan, false, ProgressListener.NONE);

        assertThat(plan.fullExtraCopiesPerRecord()).isEqualTo(1);
        assertThat(plan.residualExtraBytes()).isPositive();
        assertThat(result.outputBytes()).isBetween(target,
                plan.estimatedOutputBytes() + plan.maximumResidualOvershootBytes());
        Document expanded = parse(output);
        int orgCount = ((Element) expanded.getElementsByTagName("ET_ORG").item(0))
                .getElementsByTagName("IDOBJ").getLength();
        assertThat(orgCount).isGreaterThan(5);
        assertConnectedTreeWithUniqueIds(expanded, orgCount);
    }

    @Test
    void entireExcludedCollectionDoesNotPreventExpansionOfOtherCollections() throws Exception {
        Path source = writeTree("");
        ExpansionOptions options = fixedCopies(2).withRecordExclusions(List.of(
                RecordExclusion.parse("IDOBJ=10000001"),
                RecordExclusion.parse("IDOBJ=20000001"),
                RecordExclusion.parse("IDOBJ=30000001")));
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("persons.xml");

        ExpansionResult result = service.expand(source, output, options, plan, false, ProgressListener.NONE);

        TargetPathPlan org = plan.targetPaths().getFirst();
        assertThat(org.excludedRecordCount()).isEqualTo(3);
        assertThat(org.recordBytes()).isZero();
        assertThat(org.maxRecordBytes()).isZero();
        assertThat(org.residualExtraBytes()).isZero();
        assertThat(result.duplicatesWritten()).isEqualTo(2);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        assertConnectedTreeWithUniqueIds(parse(output), 3);
    }

    @Test
    void matchesNestedFieldsAndCombinesTextSplitByComments() throws Exception {
        Path source = writeTree("");
        Files.writeString(source, Files.readString(source)
                .replace("<OBJID>10000001</OBJID>", "<OBJID> 100<!--split-->00001 </OBJID>"));
        ExpansionOptions options = fixedCopies(1).withRecordExclusions(List.of(
                RecordExclusion.parse(" /DYN_ATTR/HRP9110/item/OBJID/ = 10000001 "),
                RecordExclusion.parse("IDOBJ=10000001")));
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);

        assertThat(plan.targetPaths().getFirst().excludedRecordCount()).isEqualTo(1);
    }

    @Test
    void rejectsUnmatchedExclusionsInsteadOfSilentlyCopyingRoot() throws Exception {
        Path source = writeTree("");
        ExpansionOptions options = fixedCopies(1).withRecordExclusions(List.of(
                RecordExclusion.parse("IDOBJ=10000001"),
                RecordExclusion.parse("UNKNOWN=10000001")));

        assertThatThrownBy(() -> service.buildPlan(source, options, ProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не найдены записи", "UNKNOWN=10000001");
    }

    @Test
    void rejectsPlanWithNoRecordsLeftToCopy() throws Exception {
        Path source = writeTree("");
        ExpansionOptions options = ExpansionOptions.fixedCopies(
                1, List.of("/asx:abap/asx:values/ET_PERSON/item"), null, null, UNIQUE_FIELDS)
                .withRecordExclusions(List.of(RecordExclusion.parse("IDPERS=40000001")));

        assertThatThrownBy(() -> service.buildPlan(source, options, ProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Все найденные записи исключены");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDOBJ", "=10000001", "IDOBJ=", "IDOBJ=  "})
    void rejectsInvalidConditions(String condition) {
        assertThatThrownBy(() -> RecordExclusion.parse(condition))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExpansionOptions fixedCopies(long copies) {
        return ExpansionOptions.fixedCopies(copies, List.of(), null, null, UNIQUE_FIELDS);
    }

    private Path writeTree(String rootParent) throws Exception {
        Path source = directory.resolve("tree.xml");
        Files.writeString(source, """
                <asx:abap xmlns:asx="urn:test"><asx:values><ET_ORG>
                <item><OBJTYPE>O</OBJTYPE><IDOBJ>10000001</IDOBJ><OBJNAME>%s</OBJNAME>
                <DYN_ATTR>%s<HRP9110><item><OBJID>10000001</OBJID></item></HRP9110></DYN_ATTR></item>
                <item><OBJTYPE>O</OBJTYPE><IDOBJ>20000001</IDOBJ><DYN_ATTR>
                <PARENT><item><VALUE>10000001</VALUE></item></PARENT>
                <HRP9110><item><OBJID>20000001</OBJID></item></HRP9110></DYN_ATTR></item>
                <item><OBJTYPE>S</OBJTYPE><IDOBJ>30000001</IDOBJ><DYN_ATTR>
                <PARENT><item><VALUE>20000001</VALUE></item></PARENT>
                <HRP9110><item><OBJID>30000001</OBJID></item></HRP9110></DYN_ATTR></item>
                </ET_ORG><ET_PERSON><item><IDPERS>40000001</IDPERS></item></ET_PERSON>
                </asx:values></asx:abap>
                """.formatted("Root".repeat(300), rootParent).replaceAll(">\\s+<", "><").strip());
        return source;
    }

    private static Document parse(Path path) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile());
    }

    private static void assertConnectedTreeWithUniqueIds(Document document, int expectedRecords) {
        Element collection = (Element) document.getElementsByTagName("ET_ORG").item(0);
        NodeList records = collection.getChildNodes();
        assertThat(records.getLength()).isEqualTo(expectedRecords);
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < records.getLength(); index++) {
            Element record = (Element) records.item(index);
            String id = record.getElementsByTagName("IDOBJ").item(0).getTextContent();
            assertThat(ids.add(id)).as("unique IDOBJ %s", id).isTrue();
            assertThat(record.getElementsByTagName("OBJID").item(0).getTextContent()).isEqualTo(id);
        }
        assertThat(ids).contains("10000001", "20000001", "30000001");
        // The original root is retained once; every other record still has an existing parent.
        for (int index = 1; index < records.getLength(); index++) {
            Element record = (Element) records.item(index);
            NodeList parents = record.getElementsByTagName("PARENT");
            assertThat(parents.getLength()).isEqualTo(1);
            String parent = ((Element) parents.item(0)).getElementsByTagName("VALUE").item(0).getTextContent();
            assertThat(ids).contains(parent);
        }
    }
}
