package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the invariants around {@link Problem} identity and {@link RoadmapEntry} membership: the same
 * {@code (platform, externalCode)} must never create a second {@link Problem} row, and the same
 * problem must never be registered twice within one {@link RoadmapStage} (#142). This is the layer
 * a workbook importer is expected to call rather than writing to the repositories directly, so
 * re-imports stay idempotent by construction.
 *
 * <p>The {@code findOrCreateProblem}/{@code addToRoadmap} convenience methods each open and close
 * their own connection, matching every other service in the codebase. The {@link Connection}-scoped
 * {@code upsertProblem}/{@code upsertRoadmapMembership} overloads exist for the workbook importer
 * (#143), which needs every row of an import to run inside one shared transaction and needs to know
 * whether each row created, updated, or merely reused existing data for its import summary.
 */
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final RoadmapEntryRepository roadmapEntryRepository;

    public ProblemService() {
        this(new ProblemRepository(), new RoadmapEntryRepository());
    }

    public ProblemService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository) {
        this.problemRepository = problemRepository;
        this.roadmapEntryRepository = roadmapEntryRepository;
    }

    public List<Problem> getAllProblems() {
        return problemRepository.findAll();
    }

    public List<Problem> getProblemsByTopic(String topic) {
        return problemRepository.findByTopic(topic);
    }

    public Optional<Problem> findById(long id) {
        return problemRepository.findById(id);
    }

    /**
     * Finds the existing problem for {@code (platform, externalCode)}, updating its catalog fields
     * in place, or creates a new one if this is the first time the code has been seen. Never
     * creates a duplicate row for a code that already exists.
     */
    public Problem findOrCreateProblem(String platform, String externalCode, String title, String url,
                                       String topic, Integer qualityRating, List<String> learningResources) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return upsertProblem(connection, platform, externalCode, title, url, topic, qualityRating, learningResources).problem();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to find or create problem", exception);
        }
    }

    /** {@link Connection}-scoped variant of {@link #findOrCreateProblem} that reports what happened, for the importer (#143). */
    public ProblemUpsertResult upsertProblem(Connection connection, String platform, String externalCode, String title, String url,
                                             String topic, Integer qualityRating, List<String> learningResources) throws SQLException {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("Problem platform is required.");
        }
        if (externalCode == null || externalCode.isBlank()) {
            throw new IllegalArgumentException("Problem external code is required.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Problem title is required.");
        }

        String encodedResources = AcceptedAnswerCodec.encode(learningResources == null ? List.of() : learningResources);
        String resourcesToStore = encodedResources.isBlank() ? null : encodedResources;
        Optional<Problem> existing = problemRepository.findByPlatformAndExternalCode(connection, platform.strip(), externalCode.strip());
        if (existing.isPresent()) {
            Problem problem = existing.get();
            String normalizedTitle = title.strip();
            boolean changed = !Objects.equals(problem.getTitle(), normalizedTitle)
                    || !Objects.equals(problem.getUrl(), url)
                    || !Objects.equals(problem.getTopic(), normalizeTopicForComparison(topic))
                    || !Objects.equals(problem.getQualityRating(), qualityRating)
                    || !Objects.equals(problem.getLearningResources(), resourcesToStore);
            if (changed) {
                problem.setTitle(normalizedTitle);
                problem.setUrl(url);
                problem.setTopic(topic);
                problem.setQualityRating(qualityRating);
                problem.setLearningResources(resourcesToStore);
                problemRepository.update(connection, problem);
            }
            Problem refreshed = problemRepository.findById(connection, problem.getId()).orElseThrow();
            return new ProblemUpsertResult(refreshed, false, changed);
        }

        Problem problem = new Problem(externalCode.strip(), platform.strip(), title.strip(), url, topic, qualityRating, resourcesToStore);
        Problem saved = problemRepository.save(connection, problem);
        return new ProblemUpsertResult(saved, true, false);
    }

    /**
     * Registers (or repositions) a problem's membership at a roadmap slot. A problem already
     * registered in {@code stage} has its position updated in place rather than gaining a second
     * membership row; a slot already held by a different problem is rejected rather than silently
     * reassigned, since that would indicate conflicting import data rather than a legitimate move.
     */
    public RoadmapEntry addToRoadmap(long problemId, RoadmapStage stage, int sequenceOrder, Integer setNumber,
                                     boolean mandatory, DifficultyLevel suggestedLevel) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return upsertRoadmapMembership(connection, problemId, stage, sequenceOrder, setNumber, mandatory, suggestedLevel).entry();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to add problem to roadmap", exception);
        }
    }

    /** {@link Connection}-scoped variant of {@link #addToRoadmap} that reports whether a new membership was created, for the importer (#143). */
    public RoadmapMembershipResult upsertRoadmapMembership(Connection connection, long problemId, RoadmapStage stage, int sequenceOrder,
                                                           Integer setNumber, boolean mandatory, DifficultyLevel suggestedLevel) throws SQLException {
        if (problemRepository.findById(connection, problemId).isEmpty()) {
            throw new IllegalArgumentException("No problem with id " + problemId + " exists.");
        }

        Optional<RoadmapEntry> existingForProblem = roadmapEntryRepository.findByProblemIdAndStage(connection, problemId, stage);
        if (existingForProblem.isPresent()) {
            RoadmapEntry entry = existingForProblem.get();
            entry.setSequenceOrder(sequenceOrder);
            entry.setSetNumber(setNumber);
            entry.setMandatory(mandatory);
            entry.setSuggestedLevel(suggestedLevel);
            roadmapEntryRepository.update(connection, entry);
            return new RoadmapMembershipResult(entry, false);
        }

        Optional<RoadmapEntry> occupant = roadmapEntryRepository.findByStageAndSequence(connection, stage, sequenceOrder);
        if (occupant.isPresent() && occupant.get().getProblemId() != problemId) {
            throw new IllegalStateException(
                    "Roadmap slot " + stage + "#" + sequenceOrder + " is already held by problem " + occupant.get().getProblemId());
        }

        RoadmapEntry saved = roadmapEntryRepository.save(connection, new RoadmapEntry(problemId, stage, sequenceOrder, setNumber, mandatory, suggestedLevel));
        return new RoadmapMembershipResult(saved, true);
    }

    public List<RoadmapEntry> getRoadmapInOrder() {
        return roadmapEntryRepository.findAllInRoadmapOrder();
    }

    public List<RoadmapEntry> getRoadmapEntriesForProblem(long problemId) {
        return roadmapEntryRepository.findByProblemId(problemId);
    }

    private String normalizeTopicForComparison(String topic) {
        return topic == null || topic.isBlank() ? "General" : topic.strip();
    }

    /** Whether {@link #upsertProblem} created a brand-new row, or found and possibly updated an existing one. */
    public record ProblemUpsertResult(Problem problem, boolean created, boolean fieldsUpdated) {
    }

    /** Whether {@link #upsertRoadmapMembership} created a brand-new membership, or repositioned an existing one. */
    public record RoadmapMembershipResult(RoadmapEntry entry, boolean created) {
    }
}
