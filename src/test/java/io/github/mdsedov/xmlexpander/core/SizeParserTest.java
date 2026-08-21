package io.github.mdsedov.xmlexpander.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SizeParserTest {

    @Test
    void parsesBinaryAndDecimalSizes() {
        assertThat(SizeParser.parse("80MiB")).isEqualTo(80L * 1024 * 1024);
        assertThat(SizeParser.parse("1.5GiB")).isEqualTo(1_610_612_736L);
        assertThat(SizeParser.parse("1600MB")).isEqualTo(1_600_000_000L);
        assertThat(SizeParser.parse("1,5 GiB")).isEqualTo(1_610_612_736L);
    }

    @Test
    void rejectsInvalidSizes() {
        assertThatThrownBy(() -> SizeParser.parse("large"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SizeParser.parse("0MiB"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
