package com.codefit.evolution.quest;

import java.util.*;

final class CatalogValues {
    private CatalogValues() {}

    static String text(String s) {
        if (s == null || s.isBlank() || s.length() > 16000)
            throw new IllegalArgumentException("Bounded nonblank metadata required");
        return s;
    }

    static <T extends Comparable<? super T>> List<T> distinct(List<T> list, int max) {
        var copy = List.copyOf(list);
        if (copy.isEmpty() || copy.size() > max || new HashSet<>(copy).size() != copy.size())
            throw new IllegalArgumentException("Distinct bounded nonempty values required");
        return copy.stream().sorted().toList();
    }
}
