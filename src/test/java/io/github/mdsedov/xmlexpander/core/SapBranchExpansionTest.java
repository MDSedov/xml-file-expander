package io.github.mdsedov.xmlexpander.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class SapBranchExpansionTest {
    private static final Path FIXTURE = Path.of("tests/fixtures/sap_branches.xml");
    private static final List<BranchReference> LINKS = List.of(
            BranchReference.parse("ET_PERSON/POSITION=org"),
            BranchReference.parse("ET_PERSON/ORG=org"),
            BranchReference.parse("ET_ORG/HOLDER=person"),
            BranchReference.parse("ET_ORG/DYN_ATTR/RELATED/item/VALUE=org"));
    private final XmlExpansionService service = new XmlExpansionService();
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 15})
    void copiesCompleteStructuresIncludingForwardAndCrossBranchReferences(long copies) throws Exception {
        ExpansionOptions options = fixed(copies, LINKS);
        ExpansionPlan plan = service.buildPlan(FIXTURE, options, ProgressListener.NONE);
        Path output = directory.resolve("branches.xml");
        ExpansionResult result = service.expand(FIXTURE, output, options, plan, false, ProgressListener.NONE);

        assertThat(plan.branches().rootCount()).isEqualTo(2);
        assertThat(plan.branches().copiedPersonCount()).isEqualTo(1);
        assertThat(plan.branches().preservedPersonCount()).isEqualTo(1);
        assertThat(result.duplicatesWritten()).isEqualTo(5 * copies);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        assertStructure(output, (int) copies, true);
    }

    @Test
    void targetSizeRoundsUpToCompleteStructures() throws Exception {
        ExpansionOptions zero = fixed(0, LINKS);
        ExpansionPlan baseline = service.buildPlan(FIXTURE, zero, ProgressListener.NONE);
        long target = baseline.originalSerializedBytes() + baseline.repeatableRecordBytes() + 1;
        ExpansionOptions options = ExpansionOptions.targetSize(target, List.of(), null, null, List.of()).withSapBranches(LINKS);
        ExpansionPlan plan = service.buildPlan(FIXTURE, options, ProgressListener.NONE);
        Path output = directory.resolve("target.xml");

        ExpansionResult result = service.expand(FIXTURE, output, options, plan, false, ProgressListener.NONE);

        assertThat(plan.fullExtraCopiesPerRecord()).isEqualTo(2);
        assertThat(plan.residualExtraBytes()).isZero();
        assertThat(plan.maximumResidualOvershootBytes()).isZero();
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes()).isGreaterThanOrEqualTo(target);
        assertThat(result.outputBytes() - target).isLessThan(baseline.repeatableRecordBytes());
        assertStructure(output, 2, true);
    }

    @Test
    void keepsPersonsWhenTheirOrgLinkPathsAreNotConfigured() throws Exception {
        ExpansionOptions options = fixed(2, List.of());
        ExpansionPlan plan = service.buildPlan(FIXTURE, options, ProgressListener.NONE);
        Path output = directory.resolve("org-only.xml");
        service.expand(FIXTURE, output, options, plan, false, ProgressListener.NONE);
        assertThat(plan.branches().copiedPersonCount()).isZero();
        assertThat(plan.branches().preservedPersonCount()).isEqualTo(2);
        assertThat(parse(output).getElementsByTagName("IDPERS").getLength()).isEqualTo(2);
    }

    @Test
    void handlesTextIdsEscapingAndSplitTextWithoutChangingNames() throws Exception {
        String xml = Files.readString(FIXTURE)
                .replace("90000001", "dept&amp;A_DUP_existing")
                .replace("<IDOBJ>90000002</IDOBJ>", "<IDOBJ> 9000<!--split-->0002 </IDOBJ>")
                .replace("<OBJID>90000002</OBJID>", "<OBJID><![CDATA[90000002]]></OBJID>");
        Path source = directory.resolve("text.xml");
        Files.writeString(source, xml);
        ExpansionOptions options = fixed(3, LINKS);
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("text-expanded.xml");
        ExpansionResult result = service.expand(source, output, options, plan, false, ProgressListener.NONE);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        assertStructure(output, 3, true);
    }

    @Test
    void explicitAnchorCanHaveAnExternalParentAndRetainsItsChildren() throws Exception {
        String xml = Files.readString(FIXTURE).replace("<OBJNAME>Root A</OBJNAME><DYN_ATTR>",
                "<OBJNAME>Root A</OBJNAME><DYN_ATTR><PARENT><item><VALUE>external-root</VALUE></item></PARENT>");
        Path source = directory.resolve("external.xml");
        Files.writeString(source, xml);
        ExpansionOptions options = fixed(2, LINKS).withRecordExclusions(List.of(RecordExclusion.parse("IDOBJ=10000367")));
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("external-expanded.xml");
        service.expand(source, output, options, plan, false, ProgressListener.NONE);
        assertStructure(output, 2, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "null", "  Null  "})
    void preservesRootsWithNullParentMarkersAndCopiesTheirBranches(String marker) throws Exception {
        String xml = Files.readString(FIXTURE).replace("<OBJNAME>Root A</OBJNAME><DYN_ATTR>",
                "<OBJNAME>Root A</OBJNAME><DYN_ATTR><PARENT><item><VALUE>" + marker + "</VALUE></item></PARENT>");
        Path source = directory.resolve("null-parent.xml");
        Files.writeString(source, xml);
        ExpansionOptions options = fixed(3, LINKS);
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("null-parent-expanded.xml");

        ExpansionResult result = service.expand(source, output, options, plan, false, ProgressListener.NONE);

        assertThat(plan.branches().rootCount()).isEqualTo(2);
        assertThat(result.duplicatesWritten()).isEqualTo(15);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        assertStructure(output, 3, true);
        Element root = groups(parse(output), "ET_ORG", "OBJNAME").get("Root A").getFirst();
        assertThat(value(root, "IDOBJ")).isEqualTo("10000367");
        assertThat(root.getElementsByTagName("VALUE").item(0).getTextContent()).isEqualTo(marker);
    }

    @Test
    void nullMarkerAlongsideARealParentDoesNotCreateAnotherRoot() throws Exception {
        String xml = Files.readString(FIXTURE).replace("<VALUE>90000001</VALUE></item></PARENT>",
                "<VALUE>90000001</VALUE></item><item><VALUE>NULL</VALUE></item></PARENT>");
        Path source = directory.resolve("mixed-parents.xml");
        Files.writeString(source, xml);
        ExpansionOptions options = fixed(2, LINKS);
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("mixed-parents-expanded.xml");

        ExpansionResult result = service.expand(source, output, options, plan, false, ProgressListener.NONE);

        assertThat(plan.branches().rootCount()).isEqualTo(2);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        assertStructure(output, 2, true);
        for (Element department : groups(parse(output), "ET_ORG", "OBJNAME").get("Department B")) {
            assertThat(department.getElementsByTagName("VALUE").item(1).getTextContent()).isEqualTo("NULL");
        }
    }

    @Test
    void nullTextIsStillAReferenceWhenItMatchesAnActualOrgIdentifier() throws Exception {
        Path source = directory.resolve("null-id.xml");
        Files.writeString(source, Files.readString(FIXTURE).replace("90000002", "NULL"));
        ExpansionOptions options = fixed(2, LINKS);
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Path output = directory.resolve("null-id-expanded.xml");

        ExpansionResult result = service.expand(source, output, options, plan, false, ProgressListener.NONE);

        assertThat(plan.branches().rootCount()).isEqualTo(2);
        assertThat(result.outputBytes()).isEqualTo(plan.estimatedOutputBytes());
        assertStructure(output, 2, true);
    }

    @Test
    void nullParentDoesNotTurnAPositionIntoARoot() throws Exception {
        assertInvalid(Files.readString(FIXTURE).replace("<VALUE>90000002</VALUE>", "<VALUE>NULL</VALUE>"),
                "нет родителя, но OBJTYPE не O");
    }

    @Test
    void nullMarkerDoesNotHideMissingReferencesInOtherFields() throws Exception {
        assertInvalid(Files.readString(FIXTURE).replace("<VALUE>90000004</VALUE>", "<VALUE>NULL</VALUE>"),
                "Не найдена цель связи DYN_ATTR/RELATED/item/VALUE=NULL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"90000001", "90000002"})
    void rejectsDanglingParentWithoutTurningItIntoAnAutomaticRoot(String parentId) throws Exception {
        assertInvalid(Files.readString(FIXTURE).replace("<VALUE>" + parentId + "</VALUE>", "<VALUE>missing</VALUE>"),
                "Не найдена цель связи");
    }

    @Test
    void rejectsCycles() throws Exception {
        assertInvalid(Files.readString(FIXTURE).replace("<VALUE>10000367</VALUE>", "<VALUE>90000002</VALUE>"), "цикл");
    }

    @Test
    void rejectsDuplicateSourceIdentifiers() throws Exception {
        assertInvalid(Files.readString(FIXTURE).replace("90000004", "90000003"), "Повторный исходный ID");
    }

    @Test
    void rejectsUnknownConfiguredField() {
        assertThatThrownBy(() -> service.buildPlan(FIXTURE,
                fixed(1, List.of(BranchReference.parse("ET_PERSON/TYPO=org"))), ProgressListener.NONE))
                .hasMessageContaining("Не найдено непустое поле связи");
    }

    @Test
    void changedSourceDoesNotReplaceExistingOutputOrLeaveTemporaryFile() throws Exception {
        Path source = directory.resolve("changing.xml");
        Files.copy(FIXTURE, source);
        ExpansionOptions options = fixed(1, LINKS);
        ExpansionPlan plan = service.buildPlan(source, options, ProgressListener.NONE);
        Files.writeString(source, Files.readString(source).replace("Department A", "Changed name"));
        Path output = directory.resolve("existing.xml");
        Files.writeString(output, "keep-me");
        assertThatThrownBy(() -> service.expand(source, output, options, plan, true, ProgressListener.NONE))
                .hasMessageContaining("изменился после анализа");
        assertThat(Files.readString(output)).isEqualTo("keep-me");
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith(".expanded-xml-"));
        }
    }

    private void assertInvalid(String xml, String message) throws Exception {
        Path source = directory.resolve("invalid.xml");
        Files.writeString(source, xml);
        assertThatThrownBy(() -> service.buildPlan(source, fixed(1, LINKS), ProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private static ExpansionOptions fixed(long copies, List<BranchReference> links) {
        return ExpansionOptions.fixedCopies(copies, List.of(), null, null, List.of()).withSapBranches(links);
    }

    private static Document parse(Path path) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile());
    }

    private static void assertStructure(Path output, int copies, boolean personsCopied) throws Exception {
        Document document = parse(output);
        Map<String, List<Element>> org = groups(document, "ET_ORG", "OBJNAME");
        Map<String, List<Element>> people = groups(document, "ET_PERSON", "NAME");
        assertThat(org.get("Root A")).hasSize(1);
        assertThat(org.get("Root B")).hasSize(1);
        assertThat(people.get("Unlinked")).hasSize(1);
        Set<String> ids = new HashSet<>();
        for (List<Element> records : org.values()) {
            for (Element record : records) {
                assertThat(ids.add(value(record, "IDOBJ"))).isTrue();
                if (record.getElementsByTagName("OBJID").getLength() > 0) {
                    assertThat(value(record, "OBJID")).isEqualTo(value(record, "IDOBJ"));
                }
            }
        }
        for (String name : List.of("Department A", "Department B", "Position A", "Position B")) {
            assertThat(org.get(name)).hasSize(copies + 1);
        }
        assertThat(people.get("Person A")).hasSize(personsCopied ? copies + 1 : 1);
        for (int copy = 0; copy <= copies; copy++) {
            Element a = org.get("Department A").get(copy);
            Element b = org.get("Department B").get(copy);
            Element position = org.get("Position A").get(copy);
            Element other = org.get("Position B").get(copy);
            assertThat(parent(a)).isEqualTo("10000367");
            assertThat(parent(b)).isEqualTo(value(a, "IDOBJ"));
            assertThat(parent(position)).isEqualTo(value(b, "IDOBJ"));
            assertThat(parent(other)).isEqualTo("00000001");
            Element related = (Element) position.getElementsByTagName("RELATED").item(0);
            assertThat(value(related, "VALUE")).isEqualTo(value(other, "IDOBJ"));
            if (personsCopied) {
                Element person = people.get("Person A").get(copy);
                assertThat(value(person, "POSITION")).isEqualTo(value(position, "IDOBJ"));
                assertThat(value(person, "ORG")).isEqualTo(value(b, "IDOBJ"));
                assertThat(value(position, "HOLDER")).isEqualTo(value(person, "IDPERS"));
                assertThat(ids.add(value(person, "IDPERS"))).isTrue();
            }
        }
        assertThat(document.getElementsByTagName("ET_OTHER").item(0).getTextContent()).contains("unchanged");
    }

    private static Map<String, List<Element>> groups(Document doc, String collection, String nameField) {
        Map<String, List<Element>> result = new LinkedHashMap<>();
        NodeList records = ((Element) doc.getElementsByTagName(collection).item(0)).getChildNodes();
        for (int i = 0; i < records.getLength(); i++) {
            if (records.item(i) instanceof Element record) {
                result.computeIfAbsent(value(record, nameField), ignored -> new ArrayList<>()).add(record);
            }
        }
        return result;
    }

    private static String value(Element record, String field) {
        return record.getElementsByTagName(field).item(0).getTextContent().strip();
    }
    private static String parent(Element record) {
        return value((Element) record.getElementsByTagName("PARENT").item(0), "VALUE");
    }
}
