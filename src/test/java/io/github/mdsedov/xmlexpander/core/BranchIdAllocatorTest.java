package io.github.mdsedov.xmlexpander.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mdsedov.xmlexpander.core.BranchIdAllocator.Key;

class BranchIdAllocatorTest {
    @Test
    void allocatesDisjointRangesAroundOriginalsIncludingSameValuesInDifferentDomains() {
        Set<Key> originals = new LinkedHashSet<>();
        for (int i = 0; i < 100; i++) {
            originals.add(new Key("org", "%06d".formatted(i * 9973)));
        }
        originals.add(new Key("person", "000000"));
        Set<Key> copied = new LinkedHashSet<>(originals);
        copied.remove(new Key("org", "000000"));
        BranchIdAllocator allocator = new BranchIdAllocator(originals, copied);
        allocator.checkCapacity(200);
        Set<String> used = new HashSet<>();
        originals.forEach(key -> used.add(key.value()));
        for (long copy = 1; copy <= 200; copy++) {
            for (Key key : copied) {
                String id = allocator.map(key, copy);
                assertThat(id).matches("[0-9]{6}");
                assertThat(used.add(id)).as("unique generated ID %s", id).isTrue();
                assertThat(allocator.map(key, copy)).isEqualTo(id);
            }
        }
        assertThat(allocator.map(new Key("org", "000000"), 1)).isEqualTo("000000");
    }

    @Test
    void reportsExhaustionInsteadOfWrappingOrChangingNumericWidth() {
        Set<Key> originals = Set.of(new Key("org", "1"), new Key("org", "9"));
        BranchIdAllocator allocator = new BranchIdAllocator(originals, originals);
        allocator.checkCapacity(3);
        assertThatThrownBy(() -> allocator.checkCapacity(4)).hasMessageContaining("Недостаточно свободных");
    }

    @Test
    void textualIdsRemainDistinctEvenWhenSourceAlreadyContainsCopySuffixes() {
        Set<Key> originals = Set.of(new Key("org", "alpha"), new Key("person", "alpha"),
                new Key("org", "alpha_DUP_O0000000000000001"));
        BranchIdAllocator allocator = new BranchIdAllocator(originals, originals);
        Set<String> used = new HashSet<>();
        originals.forEach(key -> used.add(key.value()));
        for (long copy = 1; copy < 20; copy++) {
            for (Key key : originals) {
                assertThat(used.add(allocator.map(key, copy))).isTrue();
                assertThat(allocator.map(key, copy).length()).isEqualTo(allocator.map(key, 0).length());
            }
        }
    }
}
