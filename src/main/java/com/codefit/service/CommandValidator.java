package com.codefit.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Structurally parses and compares Git/Linux command strings for
 * {@link com.codefit.model.CardType#isCommandTemplate()} cards, replacing the plain
 * whitespace-normalized full-string comparison previously used for
 * {@link com.codefit.model.ValidationMode#COMMAND_NORMALIZED}. That let equivalent flag orderings
 * be rejected (e.g. {@code ls -la} vs {@code ls -al}) while feedback could describe an entirely
 * different command as "accepted but flags are missing" just because the executable name matched.
 *
 * <p>Flags are compared as an order-insensitive set; the executable, an optional subcommand (the
 * first non-flag token), and any further positional arguments are compared in encountered order,
 * so a different executable or subcommand is never treated as a match. A flag's value is only
 * recognized when attached with {@code =} ({@code --flag=value}); a space-separated token
 * following a flag is treated as the next positional argument instead, which still compares
 * correctly across flag reordering since positional order is tracked independently of flags.</p>
 *
 * <p>Single-dash flags with more than one letter (e.g. {@code -la}) are compared by their sorted
 * letters, so grouped short options are accepted regardless of order ({@code -la} ~ {@code -al}).
 * Long ({@code --}) flags must match by exact name. Comparison is case-insensitive throughout,
 * matching the case-insensitive accepted-answer matching already used for command cards.</p>
 */
public final class CommandValidator {

    private CommandValidator() {
    }

    public static boolean matches(String attempt, String expected) {
        return compare(attempt, expected).matches();
    }

    public static Comparison compare(String attempt, String expected) {
        ParsedCommand actual = parse(attempt);
        ParsedCommand wanted = parse(expected);

        boolean executableMatches = equalsIgnoreCaseSafe(actual.executable, wanted.executable);
        boolean subcommandMatches = equalsIgnoreCaseSafe(actual.subcommand, wanted.subcommand);
        boolean positionalArgsMatch = positionalListEquals(actual.positionalArgs, wanted.positionalArgs);

        List<Flag> remainingActual = new ArrayList<>(actual.flags);
        List<String> missing = new ArrayList<>();
        List<String> incorrect = new ArrayList<>();
        for (Flag expectedFlag : wanted.flags) {
            Flag found = remainingActual.stream().filter(f -> f.sameName(expectedFlag)).findFirst().orElse(null);
            if (found == null) {
                missing.add(expectedFlag.name());
            } else {
                remainingActual.remove(found);
                if (!found.sameValue(expectedFlag)) {
                    incorrect.add(expectedFlag.name());
                }
            }
        }
        List<String> extra = remainingActual.stream().map(Flag::name).toList();

        return new Comparison(executableMatches, subcommandMatches, positionalArgsMatch, missing, extra,
                incorrect, wanted.executable, wanted.subcommand, actual.executable, actual.subcommand);
    }

    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.equalsIgnoreCase(b);
    }

    private static boolean positionalListEquals(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static ParsedCommand parse(String raw) {
        List<String> tokens = tokenize(AnswerValidator.normalizeCommand(raw == null ? "" : raw));
        if (tokens.isEmpty()) {
            return new ParsedCommand("", null, List.of(), List.of());
        }
        String executable = tokens.get(0);
        String subcommand = null;
        List<String> positionalArgs = new ArrayList<>();
        List<Flag> flags = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.length() > 1 && token.charAt(0) == '-') {
                int equalsIndex = token.indexOf('=');
                String name = equalsIndex >= 0 ? token.substring(0, equalsIndex) : token;
                String value = equalsIndex >= 0 ? token.substring(equalsIndex + 1) : null;
                flags.add(new Flag(name, value));
            } else if (subcommand == null) {
                subcommand = token;
            } else {
                positionalArgs.add(token);
            }
        }
        return new ParsedCommand(executable, subcommand, positionalArgs, flags);
    }

    /** Minimal shell-style tokenizer: splits on whitespace but keeps single/double-quoted spans intact. */
    private static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        Character quote = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != null) {
                if (c == quote) {
                    quote = null;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            current.append(c);
            inToken = true;
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private record ParsedCommand(String executable, String subcommand, List<String> positionalArgs,
                                  List<Flag> flags) {
    }

    private record Flag(String name, String value) {
        boolean sameName(Flag other) {
            if (name.startsWith("--") || other.name.startsWith("--")) {
                return name.equalsIgnoreCase(other.name);
            }
            return sortedLetters(name).equalsIgnoreCase(sortedLetters(other.name));
        }

        boolean sameValue(Flag other) {
            return Objects.equals(normalize(value), normalize(other.value));
        }

        private static String normalize(String value) {
            return value == null ? null : value.toLowerCase();
        }

        private static String sortedLetters(String flagName) {
            char[] chars = flagName.replaceFirst("^-+", "").toLowerCase().toCharArray();
            Arrays.sort(chars);
            return new String(chars);
        }
    }

    /**
     * Structural diff between a submitted command and one accepted answer. {@link #matches()} is
     * {@code false} whenever the executable or subcommand differ, regardless of how many flags
     * happen to line up, so a different command is never reported as accepted.
     */
    public record Comparison(boolean executableMatches, boolean subcommandMatches, boolean positionalArgsMatch,
                              List<String> missingFlags, List<String> extraFlags, List<String> incorrectFlagValues,
                              String expectedExecutable, String expectedSubcommand, String actualExecutable,
                              String actualSubcommand) {

        public boolean matches() {
            return executableMatches && subcommandMatches && positionalArgsMatch
                    && missingFlags.isEmpty() && extraFlags.isEmpty() && incorrectFlagValues.isEmpty();
        }

        /** Lower is a closer match; used to pick the most relevant accepted answer for feedback. */
        public int mismatchCount() {
            int count = 0;
            count += executableMatches ? 0 : 1;
            count += subcommandMatches ? 0 : 1;
            count += positionalArgsMatch ? 0 : 1;
            count += missingFlags.size() + extraFlags.size() + incorrectFlagValues.size();
            return count;
        }
    }
}
