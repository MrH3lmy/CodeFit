package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.util.List;
import java.util.Optional;

/**
 * Owns the invariants around {@link Problem} identity and {@link RoadmapEntry} membership: the same
 * {@code (platform, externalCode)} must never create a second {@link Problem} row, and the same
 * problem must never be registered twice within one {@link RoadmapStage} (#142). This is the layer
 * a future workbook importer (#143) is expected to call rather than writing to the repositories
 * directly, so re-imports stay idempotent by construction.
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
        Optional<Problem> existing = problemRepository.findByPlatformAndExternalCode(platform.strip(), externalCode.strip());
        if (existing.isPresent()) {
            Problem problem = existing.get();
            problem.setTitle(title.strip());
            problem.setUrl(url);
            problem.setTopic(topic);
            problem.setQualityRating(qualityRating);
            problem.setLearningResources(encodedResources.isBlank() ? null : encodedResources);
            problemRepository.update(problem);
            return problemRepository.findById(problem.getId()).orElseThrow();
        }

        Problem problem = new Problem(externalCode.strip(), platform.strip(), title.strip(), url, topic,
                qualityRating, encodedResources.isBlank() ? null : encodedResources);
        return problemRepository.save(problem);
    }

    /**
     * Registers (or repositions) a problem's membership at a roadmap slot. A problem already
     * registered in {@code stage} has its position updated in place rather than gaining a second
     * membership row; a slot already held by a different problem is rejected rather than silently
     * reassigned, since that would indicate conflicting import data rather than a legitimate move.
     */
    public RoadmapEntry addToRoadmap(long problemId, RoadmapStage stage, int sequenceOrder, Integer setNumber,
                                     boolean mandatory, DifficultyLevel suggestedLevel) {
        if (problemRepository.findById(problemId).isEmpty()) {
            throw new IllegalArgumentException("No problem with id " + problemId + " exists.");
        }

        Optional<RoadmapEntry> existingForProblem = roadmapEntryRepository.findByProblemIdAndStage(problemId, stage);
        if (existingForProblem.isPresent()) {
            RoadmapEntry entry = existingForProblem.get();
            entry.setSequenceOrder(sequenceOrder);
            entry.setSetNumber(setNumber);
            entry.setMandatory(mandatory);
            entry.setSuggestedLevel(suggestedLevel);
            roadmapEntryRepository.update(entry);
            return entry;
        }

        Optional<RoadmapEntry> occupant = roadmapEntryRepository.findByStageAndSequence(stage, sequenceOrder);
        if (occupant.isPresent() && occupant.get().getProblemId() != problemId) {
            throw new IllegalStateException(
                    "Roadmap slot " + stage + "#" + sequenceOrder + " is already held by problem " + occupant.get().getProblemId());
        }

        return roadmapEntryRepository.save(new RoadmapEntry(problemId, stage, sequenceOrder, setNumber, mandatory, suggestedLevel));
    }

    public List<RoadmapEntry> getRoadmapInOrder() {
        return roadmapEntryRepository.findAllInRoadmapOrder();
    }

    public List<RoadmapEntry> getRoadmapEntriesForProblem(long problemId) {
        return roadmapEntryRepository.findByProblemId(problemId);
    }
}
