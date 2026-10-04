package com.codefit.peer.match;

/** Which side of a {@link StudyMatch} this local device is. */
public enum MatchRole {
    /** This device proposed the match (sent {@code MatchInvitation}). */
    CHALLENGER,
    /** This device was invited (sends {@code MatchResponse}). */
    OPPONENT
}
