package io.github.mdsedov.xmlexpander.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Allocates disjoint copy ranges without retaining IDs for every generated record. */
final class BranchIdAllocator {
    record Key(String domain, String value) { }

    private final Map<Integer, NumericSpace> spaces = new HashMap<>();
    private final Map<Key, Long> ordinal = new LinkedHashMap<>();
    private final Set<Key> copied;
    private final String marker;

    BranchIdAllocator(Set<Key> originals, Set<Key> copied) {
        this.copied = Set.copyOf(copied);
        String candidate = "_DUP_";
        while (containsMarker(originals, candidate)) candidate += "_";
        marker = candidate;
        Map<Integer, Set<BigInteger>> reserved = new HashMap<>();
        for (Key key : originals) {
            if (numeric(key.value())) {
                reserved.computeIfAbsent(key.value().length(), ignored -> new HashSet<>())
                        .add(new BigInteger(key.value()));
            }
        }
        for (Key key : copied) {
            if (numeric(key.value())) {
                NumericSpace space = spaces.computeIfAbsent(key.value().length(), width ->
                        new NumericSpace(width, reserved.get(width)));
                ordinal.put(key, space.count++);
            } else if (key.value().length() + marker.length() + 17 > 255) {
                throw new IllegalArgumentException("Новый текстовый ID превысит 255 символов");
            }
        }
    }

    void checkCapacity(long copies) {
        for (NumericSpace space : spaces.values()) {
            BigInteger needed = BigInteger.valueOf(copies).multiply(BigInteger.valueOf(space.count));
            if (needed.compareTo(space.capacity) > 0) {
                throw new IllegalArgumentException("Недостаточно свободных числовых ID длиной " + space.width
                        + ": нужно " + needed + ", доступно " + space.capacity
                        + ". Уменьшите число копий или целевой размер.");
            }
        }
    }

    String map(Key key, long copy) {
        if (!copied.contains(key)) return key.value();
        if (!numeric(key.value())) {
            return key.value() + marker + (key.domain().equals("org") ? "O" : "P")
                    + "%016x".formatted(Math.max(1, copy));
        }
        // Copy zero is only used for exact size measurement: numeric IDs keep their width.
        if (copy == 0) return key.value();
        NumericSpace space = spaces.get(key.value().length());
        BigInteger rank = BigInteger.valueOf(copy - 1).multiply(BigInteger.valueOf(space.count))
                .add(BigInteger.valueOf(ordinal.get(key)));
        String value = space.at(rank).toString();
        return "0".repeat(space.width - value.length()) + value;
    }

    private static boolean numeric(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean containsMarker(Set<Key> originals, String marker) {
        return originals.stream().anyMatch(key -> key.value().contains(marker));
    }

    private static final class NumericSpace {
        final int width;
        final List<Gap> gaps = new ArrayList<>();
        BigInteger capacity = BigInteger.ZERO;
        long count;

        NumericSpace(int width, Set<BigInteger> reserved) {
            this.width = width;
            BigInteger next = BigInteger.ONE; // Never generate the all-zero sentinel.
            for (BigInteger used : reserved.stream().sorted(Comparator.naturalOrder()).toList()) {
                if (used.compareTo(next) > 0) addGap(next, used);
                next = next.max(used.add(BigInteger.ONE));
            }
            BigInteger limit = BigInteger.TEN.pow(width);
            if (next.compareTo(limit) < 0) addGap(next, limit);
        }

        private void addGap(BigInteger start, BigInteger end) {
            BigInteger size = end.subtract(start);
            gaps.add(new Gap(start, capacity, capacity.add(size)));
            capacity = capacity.add(size);
        }

        BigInteger at(BigInteger rank) {
            if (rank.signum() < 0 || rank.compareTo(capacity) >= 0) {
                throw new IllegalArgumentException("Исчерпано пространство уникальных ID");
            }
            int low = 0;
            int high = gaps.size() - 1;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (rank.compareTo(gaps.get(middle).endRank()) < 0) high = middle;
                else low = middle + 1;
            }
            Gap gap = gaps.get(low);
            return gap.start().add(rank.subtract(gap.startRank()));
        }
    }

    private record Gap(BigInteger start, BigInteger startRank, BigInteger endRank) { }
}
