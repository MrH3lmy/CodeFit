package com.codefit.model;

import java.util.List;
import java.util.Set;

/**
 * Structured grading configuration for {@link CardType#REGEX_PATTERN} cards under
 * {@link ValidationMode#REGEX_EXAMPLES}: the learner's submitted pattern is compiled and executed
 * against these example strings rather than compared as text against a saved pattern, so any pattern
 * equivalent to the intended one is accepted and a pattern that merely looks similar but matches a
 * different language is rejected.
 */
public record RegexCardConfig(List<String> mustMatch, List<String> mustNotMatch, Set<RegexCardFlag> flags,
                               RegexMatchMode matchMode) {

    public RegexCardConfig {
        mustMatch = mustMatch == null ? List.of() : List.copyOf(mustMatch);
        mustNotMatch = mustNotMatch == null ? List.of() : List.copyOf(mustNotMatch);
        flags = flags == null ? Set.of() : Set.copyOf(flags);
        matchMode = matchMode == null ? RegexMatchMode.FIND : matchMode;
    }
}
