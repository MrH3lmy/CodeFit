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
    CHALLENGE_PARTICIPATION(5);

    private final int code;

    SharingScope(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
