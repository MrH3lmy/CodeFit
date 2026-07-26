package com.codefit.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProblemTest {

    @Test
    void blankOrMissingTopicDefaultsToGeneral() {
        Problem blankTopic = new Problem("1", "LeetCode", "Two Sum", null, "   ", null, null);
        Problem nullTopic = new Problem("1", "LeetCode", "Two Sum", null, null, null, null);

        assertEquals("General", blankTopic.getTopic());
        assertEquals("General", nullTopic.getTopic());
    }

    @Test
    void topicIsStrippedOfSurroundingWhitespace() {
        Problem problem = new Problem("1", "LeetCode", "Two Sum", null, "  Arrays  ", null, null);

        assertEquals("Arrays", problem.getTopic());
    }
}
