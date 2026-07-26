package com.codefit.model;

import java.time.LocalDateTime;

/**
 * The identity of a single problem-solving exercise, entirely independent of the flashcard/review
 * model. A {@code Problem} is a pure catalog entry: what it is, where it lives, and how good it is
 * rated as curated content. It says nothing about where any particular learner stands on it (see
 * {@link ProblemProgress}), how many times they have submitted (see {@link ProblemAttempt}), or
 * which roadmap position(s) reference it (see {@link RoadmapEntry}).
 *
 * <p>Identity is anchored on {@code (platform, externalCode)}: the same problem can be imported
 * repeatedly, or referenced by more than one {@link RoadmapEntry} across different roadmap stages,
 * without ever creating a second {@code Problem} row (see {@code ProblemRepository#findByPlatformAndExternalCode}).
 *
 * <p>{@code learningResources} stores zero or more reference links (editorial, video walkthrough,
 * article) using the same list-of-strings codec as a flashcard's accepted answers
 * ({@link com.codefit.service.AcceptedAnswerCodec}); it is a generic "list of short strings" format,
 * not something specific to answer grading.
 */
public class Problem {
    private long id;
    private String externalCode;
    private String platform;
    private String title;
    private String url;
    private String topic;
    private Integer qualityRating;
    private String learningResources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Problem(long id, String externalCode, String platform, String title, String url, String topic,
                   Integer qualityRating, String learningResources, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.externalCode = externalCode;
        this.platform = platform;
        this.title = title;
        this.url = url;
        this.topic = normalizeTopic(topic);
        this.qualityRating = qualityRating;
        this.learningResources = learningResources;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Problem(String externalCode, String platform, String title, String url, String topic,
                   Integer qualityRating, String learningResources) {
        this(0, externalCode, platform, title, url, topic, qualityRating, learningResources, null, null);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getExternalCode() { return externalCode; }
    public void setExternalCode(String externalCode) { this.externalCode = externalCode; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = normalizeTopic(topic); }
    public Integer getQualityRating() { return qualityRating; }
    public void setQualityRating(Integer qualityRating) { this.qualityRating = qualityRating; }
    public String getLearningResources() { return learningResources; }
    public void setLearningResources(String learningResources) { this.learningResources = learningResources; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    private String normalizeTopic(String topic) {
        return topic == null || topic.isBlank() ? "General" : topic.strip();
    }
}
