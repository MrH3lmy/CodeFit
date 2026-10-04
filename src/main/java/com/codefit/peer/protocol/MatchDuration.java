package com.codefit.peer.protocol;

/** The predefined, closed duration set a Study Match challenger may choose from (V1: "keep it small"). */
public enum MatchDuration implements WireCode {
    FIFTEEN_MINUTES(1, 15),
    THIRTY_MINUTES(2, 30),
    SIXTY_MINUTES(3, 60);

    private final int code;
    private final int minutes;

    MatchDuration(int code, int minutes) {
        this.code = code;
        this.minutes = minutes;
    }

    @Override
    public int code() {
        return code;
    }

    public int minutes() {
        return minutes;
    }

    public static MatchDuration ofMinutes(int minutes) {
        for (MatchDuration duration : values()) {
            if (duration.minutes == minutes) {
                return duration;
            }
        }
        throw new IllegalArgumentException("Not one of the predefined match durations: " + minutes + " minutes.");
    }
}
