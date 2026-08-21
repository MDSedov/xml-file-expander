package io.github.mdsedov.xmlexpander.core;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SizeParser {

    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)\\s*([kmgt]?i?b?|[kmgt])?$",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Long> MULTIPLIERS = Map.ofEntries(
            Map.entry("", 1L),
            Map.entry("b", 1L),
            Map.entry("k", 1_000L),
            Map.entry("kb", 1_000L),
            Map.entry("m", 1_000_000L),
            Map.entry("mb", 1_000_000L),
            Map.entry("g", 1_000_000_000L),
            Map.entry("gb", 1_000_000_000L),
            Map.entry("t", 1_000_000_000_000L),
            Map.entry("tb", 1_000_000_000_000L),
            Map.entry("kib", 1_024L),
            Map.entry("mib", 1_048_576L),
            Map.entry("gib", 1_073_741_824L),
            Map.entry("tib", 1_099_511_627_776L));

    private SizeParser() {
    }

    public static long parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Целевой размер не указан");
        }

        Matcher matcher = SIZE_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Некорректный размер: " + value + ". Примеры: 80MiB, 1.5GiB, 1600MB");
        }

        BigDecimal number = new BigDecimal(matcher.group(1).replace(',', '.'));
        String unit = matcher.group(2) == null
                ? ""
                : matcher.group(2).toLowerCase(Locale.ROOT);
        Long multiplier = MULTIPLIERS.get(unit);
        if (multiplier == null) {
            throw new IllegalArgumentException("Неизвестная единица размера: " + unit);
        }

        long result;
        try {
            result = number.multiply(BigDecimal.valueOf(multiplier)).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Размер слишком велик или содержит лишние знаки", exception);
        }
        if (result <= 0) {
            throw new IllegalArgumentException("Размер должен быть больше нуля");
        }
        return result;
    }
}
