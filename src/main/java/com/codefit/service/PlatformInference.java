package com.codefit.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Infers a judge/platform name from a problem's external code and/or its URL, for workbooks (like
 * the real Junior Training Sheet, #159) that carry a platform-prefixed code (e.g. {@code CF677-D2-A},
 * {@code UVA 374}) but no explicit platform column. Prefix matching is tried first since it is
 * available even when a row has no resolvable URL; the URL's host is a fallback (and a cross-check)
 * for codes that don't carry a recognizable prefix.
 *
 * <p>Neither list claims to be exhaustive. A code/URL this class doesn't recognize simply yields
 * {@link Optional#empty()}, leaving the caller free to fall back to a generic default rather than
 * guessing at a platform name.
 */
final class PlatformInference {

    private PlatformInference() {
    }

    /** Longest/most specific prefixes first, so "LIVEARCHIVE" is tried before a hypothetical "LI". */
    private static final Map<String, String> CODE_PREFIXES = orderedMap(
            "LIVEARCHIVE", "UVA Live Archive",
            "HACKR", "HackerRank",
            "CF", "Codeforces",
            "UVA", "UVA",
            "SPOJ", "SPOJ",
            "TIMUS", "Timus",
            "SRM", "TopCoder",
            "ZOJ", "ZOJ",
            "PKU", "POJ",
            "POJ", "POJ",
            "ATCODER", "AtCoder",
            "LEETCODE", "LeetCode",
            "CODECHEF", "CodeChef",
            "HDU", "HDU");

    private static final Map<String, String> URL_HOST_SUFFIXES = orderedMap(
            "codeforces.com", "Codeforces",
            "icpcarchive.ecs.baylor.edu", "UVA Live Archive",
            "onlinejudge.org", "UVA",
            "spoj.com", "SPOJ",
            "acm.timus.ru", "Timus",
            "topcoder.com", "TopCoder",
            "acm.zju.edu.cn", "ZOJ",
            "poj.org", "POJ",
            "atcoder.jp", "AtCoder",
            "leetcode.com", "LeetCode",
            "codechef.com", "CodeChef",
            "hackerrank.com", "HackerRank");

    /** Infers a platform name from {@code code} (tried first) then {@code url}; empty if neither matches. */
    static Optional<String> infer(String code, String url) {
        Optional<String> fromCode = inferFromCode(code);
        if (fromCode.isPresent()) {
            return fromCode;
        }
        return inferFromUrl(url);
    }

    private static Optional<String> inferFromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String upper = code.strip().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : CODE_PREFIXES.entrySet()) {
            if (upper.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> inferFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String host;
        try {
            host = URI.create(url.strip()).getHost();
        } catch (RuntimeException malformedUrl) {
            return Optional.empty();
        }
        if (host == null) {
            return Optional.empty();
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : URL_HOST_SUFFIXES.entrySet()) {
            if (lowerHost.equals(entry.getKey()) || lowerHost.endsWith("." + entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> orderedMap(String... keyValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
