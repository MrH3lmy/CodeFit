package com.codefit.service;

import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.SolvedWith;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the two Problem Library views (#144) from the same underlying {@link Problem}/
 * {@link RoadmapEntry}/{@link ProblemProgress} data, so switching between them never duplicates or
 * diverges from the learner's actual progress:
 *
 * <ul>
 *   <li>{@link #getBlindOrderEntries()} — one row per roadmap membership, in roadmap order
 *       (stage A..D3, then position within the stage). This is the default learning mode.</li>
 *   <li>{@link #getTopicBasedEntries()} — one row per problem, regardless of how many roadmap
 *       stages it belongs to, for browsing/filtering by topic instead of following the roadmap
 *       sequence.</li>
 * </ul>
 *
 * <p>Reading these views never writes anything: a problem with no {@link ProblemProgress} row yet
 * is represented with a transient {@code NOT_STARTED} default rather than eagerly inserting one.
 */
public class ProblemLibraryService {

    private final ProblemRepository problemRepository;
    private final RoadmapEntryRepository roadmapEntryRepository;
    private final ProblemProgressRepository progressRepository;

    public ProblemLibraryService() {
        this(new ProblemRepository(), new RoadmapEntryRepository(), new ProblemProgressRepository());
    }

    public ProblemLibraryService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository,
                                 ProblemProgressRepository progressRepository) {
        this.problemRepository = problemRepository;
        this.roadmapEntryRepository = roadmapEntryRepository;
        this.progressRepository = progressRepository;
    }

    /**
     * Builds Blind Order from exactly three bulk queries — {@code roadmap_entries},
     * {@code problems}, and {@code problem_progress}, each loaded once — rather than one repository
     * call per roadmap membership (#166). At curriculum scale (~926 memberships) the old per-row
     * {@code findById}/{@code findByProblemId} loop opened well over a thousand individual SQLite
     * connections; joining the three already-bulk-loadable tables in memory instead keeps this to a
     * bounded, size-independent number of round trips.
     */
    public List<ProblemLibraryEntry> getBlindOrderEntries() {
        List<RoadmapEntry> roadmapEntries = roadmapEntryRepository.findAllInRoadmapOrder();
        Map<Long, Problem> problemsById = indexById(problemRepository.findAll(), Problem::getId);
        Map<Long, ProblemProgress> progressByProblemId = indexById(progressRepository.findAll(), ProblemProgress::getProblemId);

        return roadmapEntries.stream()
                .map(entry -> {
                    Problem problem = problemsById.get(entry.getProblemId());
                    if (problem == null) {
                        throw new IllegalStateException("Roadmap entry references missing problem " + entry.getProblemId());
                    }
                    return ProblemLibraryEntry.of(problem, entry, progressByProblemId.get(problem.getId()));
                })
                .toList();
    }

    /**
     * Builds the Topics view the same bulk-query way {@link #getBlindOrderEntries()} does (#166): one
     * row per problem, paired with its earliest-stage roadmap membership (if any) picked in memory
     * from the same already-loaded, roadmap-ordered list rather than a per-problem
     * {@code findByProblemId} query.
     */
    public List<ProblemLibraryEntry> getTopicBasedEntries() {
        List<RoadmapEntry> roadmapEntries = roadmapEntryRepository.findAllInRoadmapOrder();
        Map<Long, RoadmapEntry> primaryEntryByProblemId = new HashMap<>();
        for (RoadmapEntry entry : roadmapEntries) {
            primaryEntryByProblemId.putIfAbsent(entry.getProblemId(), entry);
        }
        Map<Long, ProblemProgress> progressByProblemId = indexById(progressRepository.findAll(), ProblemProgress::getProblemId);

        return problemRepository.findAll().stream()
                .map(problem -> ProblemLibraryEntry.of(problem, primaryEntryByProblemId.get(problem.getId()),
                        progressByProblemId.get(problem.getId())))
                .toList();
    }

    /** True the moment the roadmap has at least one problem — a single {@code COUNT(*)} rather than
     *  loading every row just to check for emptiness (#166). */
    public boolean hasAnyProblems() {
        return problemRepository.countAll() > 0;
    }

    private static <T> Map<Long, T> indexById(List<T> rows, java.util.function.ToLongFunction<T> idFunction) {
        return rows.stream().collect(Collectors.toMap(idFunction::applyAsLong, row -> row));
    }

    /** Applies every non-null field of {@code filter}; a row must match all of them. */
    public List<ProblemLibraryEntry> applyFilter(List<ProblemLibraryEntry> entries, ProblemLibraryFilter filter) {
        return entries.stream().filter(entry -> matches(entry, filter)).toList();
    }

    /**
     * Chooses the guided curriculum frontier in roadmap order while keeping failed/in-progress work in
     * the revisit queue instead of repeatedly trapping the learner on the same row. Untouched mandatory
     * work is always preferred over untouched optional work. Once every untouched position has been
     * attempted, the method falls back to earlier in-progress/revisit positions so nothing is lost.
     *
     * <p>This keeps Blind Order as the default while allowing the workspace's explicit "Next Curriculum
     * Problem" action to advance after a failed attempt. The failed position remains visible through
     * {@link #getRevisitQueue(List)} and never loses its progress state.
     */
    public Optional<ProblemLibraryEntry> getNextRecommendedProblem() {
        return getNextRecommendedProblem(getBlindOrderEntries());
    }

    /** Reuses a Blind Order list the caller already fetched instead of re-querying (#166) — callers
     *  building more than one Today-panel figure from the same snapshot (see
     *  {@code GuidedPracticeService#buildTodayPlan}) should fetch {@link #getBlindOrderEntries()} once
     *  and pass it to both this and {@link #getRevisitQueue(List)}. */
    public Optional<ProblemLibraryEntry> getNextRecommendedProblem(List<ProblemLibraryEntry> blindOrderEntries) {
        return selectNextRecommended(blindOrderEntries);
    }

    /** Package-visible, DB-free selection logic (mirrors {@code ProblemDashboardService}'s
     *  static-method-over-plain-lists convention) so the recommendation rule is unit testable directly
     *  against a hand-built list, without the shared test database's cross-test noise. */
    static Optional<ProblemLibraryEntry> selectNextRecommended(List<ProblemLibraryEntry> blindOrder) {
        Optional<ProblemLibraryEntry> untouchedMandatory = blindOrder.stream()
                .filter(entry -> entry.progress().getState() == ProblemState.NOT_STARTED)
                .filter(entry -> entry.roadmapEntry() == null || entry.roadmapEntry().isMandatory())
                .findFirst();
        if (untouchedMandatory.isPresent()) {
            return untouchedMandatory;
        }

        Optional<ProblemLibraryEntry> untouched = blindOrder.stream()
                .filter(entry -> entry.progress().getState() == ProblemState.NOT_STARTED)
                .findFirst();
        if (untouched.isPresent()) {
            return untouched;
        }

        Optional<ProblemLibraryEntry> unfinishedMandatory = blindOrder.stream()
                .filter(entry -> entry.progress().getState() != ProblemState.SOLVED)
                .filter(entry -> entry.roadmapEntry() == null || entry.roadmapEntry().isMandatory())
                .findFirst();
        if (unfinishedMandatory.isPresent()) {
            return unfinishedMandatory;
        }

        return blindOrder.stream()
                .filter(entry -> entry.progress().getState() != ProblemState.SOLVED)
                .findFirst();
    }

    /**
     * Roadmap positions that need deliberate follow-up, in Blind Order, without changing their main
     * roadmap state (#161): explicitly failed positions ({@code NEEDS_REVISIT}), submitted/in-progress
     * positions, and solved positions whose recorded assistance shows hint/editorial/solution
     * dependence. A hint-dependent solved problem remains {@code SOLVED}; revisit is supplemental and
     * therefore does not damage completion percentages or the main roadmap order.
     */
    public List<ProblemLibraryEntry> getRevisitQueue() {
        return getRevisitQueue(getBlindOrderEntries());
    }

    /** Reuses a Blind Order list the caller already fetched instead of re-querying (#166) — see
     *  {@link #getNextRecommendedProblem(List)}. */
    public List<ProblemLibraryEntry> getRevisitQueue(List<ProblemLibraryEntry> blindOrderEntries) {
        return blindOrderEntries.stream()
                .filter(entry -> needsRevisit(entry.progress()))
                .toList();
    }

    private static boolean needsRevisit(ProblemProgress progress) {
        if (progress.getState() == ProblemState.NEEDS_REVISIT || progress.getState() == ProblemState.IN_PROGRESS) {
            return true;
        }
        if (progress.getState() != ProblemState.SOLVED) {
            return false;
        }
        SolvedWith solvedWith = progress.getSolvedWith();
        return solvedWith == SolvedWith.HINT
                || solvedWith == SolvedWith.EDITORIAL
                || solvedWith == SolvedWith.SOLUTION;
    }

    public List<String> getDistinctTopics() {
        return problemRepository.findAll().stream()
                .map(Problem::getTopic)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<String> getDistinctPlatforms() {
        return problemRepository.findAll().stream()
                .map(Problem::getPlatform)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean matches(ProblemLibraryEntry entry, ProblemLibraryFilter filter) {
        Problem problem = entry.problem();
        if (filter.searchText() != null) {
            String needle = filter.searchText().strip().toLowerCase(Locale.ROOT);
            boolean matchesSearch = problem.getTitle().toLowerCase(Locale.ROOT).contains(needle)
                    || problem.getExternalCode().toLowerCase(Locale.ROOT).contains(needle)
                    || problem.getPlatform().toLowerCase(Locale.ROOT).contains(needle);
            if (!matchesSearch) {
                return false;
            }
        }
        if (filter.topic() != null && !filter.topic().equalsIgnoreCase(problem.getTopic())) {
            return false;
        }
        if (filter.stage() != null && (entry.roadmapEntry() == null || entry.roadmapEntry().getStage() != filter.stage())) {
            return false;
        }
        if (filter.suggestedLevel() != null
                && (entry.roadmapEntry() == null || entry.roadmapEntry().getSuggestedLevel() != filter.suggestedLevel())) {
            return false;
        }
        if (filter.minQualityRating() != null
                && (problem.getQualityRating() == null || problem.getQualityRating() < filter.minQualityRating())) {
            return false;
        }
        if (filter.platform() != null && !filter.platform().equalsIgnoreCase(problem.getPlatform())) {
            return false;
        }
        if (filter.state() != null && entry.progress().getState() != filter.state()) {
            return false;
        }
        return true;
    }
}
