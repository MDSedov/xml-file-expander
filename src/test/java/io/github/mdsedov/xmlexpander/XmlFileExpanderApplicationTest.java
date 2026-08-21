package io.github.mdsedov.xmlexpander;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "xml-expander.work-directory=${java.io.tmpdir}/xml-file-expander-test-work",
        "xml-expander.default-output-directory=${java.io.tmpdir}/xml-file-expander-test-output"
})
class XmlFileExpanderApplicationTest {

    @Test
    void contextLoads() {
    }
}
