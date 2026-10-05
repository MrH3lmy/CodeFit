package com.codefit.peer.protocol;

/**
 * Period kinds for comparisons. Rolling windows are computed locally, never exchanged in v1.
 *
 * <p>{@code MATCH} (added for the 1-v-1 Study Match feature) is not a calendar period: its
 * {@code start}/{@code end} are an explicit, mutually-agreed instant interval (a match's
 * {@code startedAt}/{@code endsAt}), identical on both participants' own devices by construction -
 * never independently computed from a local date and zone the way {@code DAY}/{@code WEEK} are.
 */
public enum WindowKind implements WireCode {
    DAY(1),
    WEEK(2),
    MATCH(3);

    private final int code;

    WindowKind(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
