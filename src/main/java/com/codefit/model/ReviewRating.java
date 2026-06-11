package com.codefit.model;

public enum ReviewRating {
    AGAIN(0, 0),
    HARD(1, 5),
    GOOD(2, 10),
    EASY(3, 15);

    private final int score;
    private final int xp;

    ReviewRating(int score, int xp) {
        this.score = score;
        this.xp = xp;
    }

    public int getScore() {
        return score;
    }

    public int getXp() {
        return xp;
    }
}
