package com.codefit.peer.protocol;

/**
 * What an author may share with one recipient. Every scope defaults to <em>not shared</em>; only a
 * {@link ConsentRevision} naming the scope authorizes it, and a later revision without it revokes it.
 * There is no scope for raw content: problem statements, answers, source code, notes, imported
 * workbook text, tokens, keys, or database rows can never be shared (see
 * {@code docs/problem-solving-source-attribution.md}).
 */
public enum SharingScope implements WireCode {
    SOCIAL_PROFILE(1),
    DAILY_SUMMARY(2),
    WEEKLY_SUMMARY(3),
    PREPARATION_SNAPSHOT(4),
    CHALLENGE_PARTICIPATION(5),
    /**
     * Progress sharing for one accepted 1-v-1 Study Match ({@code MessageType#PROGRESS_SUMMARY} with
     * a {@code WindowKind#MATCH} window). Distinct from {@link #CHALLENGE_PARTICIPATION}, which is
     * reserved for #186's own, separate, later group-challenge epic - this scope is granted
     * automatically, mutually, the moment a match is accepted (never a separate manual toggle).
     */
    MATCH_PARTICIPATION(6);

    private final int code;

    SharingScope(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
