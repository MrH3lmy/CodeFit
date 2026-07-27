package com.codefit.service;

import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.repository.ProblemProgressRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    public List<ProblemLibraryEntry> getBlindOrderEntries() {
        return roadmapEntryRepository.findAllInRoadmapOrder().stream()
                .map(entry -> {
                    Problem problem = problemRepository.findById(entry.getProblemId())
                            .orElseThrow(() -> new IllegalStateException("Roadmap entry references missing problem " + entry.getProblemId()));
                    return ProblemLibraryEntry.of(problem, entry, progressRepository.findByProblemId(problem.getId()).orElse(null));
                })
                .toList();
    }

    public List<ProblemLibraryEntry> getTopicBasedEntries() {
        return problemRepository.findAll().stream()
                .map(problem -> {
                    RoadmapEntry primaryEntry = roadmapEntryRepository.findByProblemId(problem.getId()).stream().findFirst().orElse(null);
                    return ProblemLibraryEntry.of(problem, primaryEntry, progressRepository.findByProblemId(problem.getId()).orElse(null));
                })
                .toList();
    }

    /** Applies every non-null field of {@code filter}; a row must match all of them. */
    public List<ProblemLibraryEntry> applyFilter(List<ProblemLibraryEntry> entries, ProblemLibraryFilter filter) {
        return entries.stream().filter(entry -> matches(entry, filter)).toList();
    }

    /**
     * The first Blind Order row that isn't yet {@code SOLVED}, preferring mandatory work: while any
     * mandatory roadmap position remains unsolved, this is the first such position (never a
     * later-stage or optional one) — see #161's "do not introduce a later-stage problem while
     * required earlier work remains". Once every mandatory position is solved, this falls through to
     * the first unsolved position overall (mandatory or optional), so optional work is recommended
     * rather than left to stall the roadmap forever.
     *
     * <p>This is the <em>default, guided</em> recommendation only — a learner can always start any
     * specific problem directly from its own row (see {@code ProblemsController}'s per-row Start
     * action), which is the "unless the learner explicitly overrides" escape hatch; this method never
     * needs its own separate override parameter because of that.
     *
     * <p>A workbook status of {@code ACX} (accepted after retries) is imported as
     * {@link ProblemState#SOLVED} the same as a plain {@code AC} (see
     * {@code TrainingSheetImportService}), so excluding {@code SOLVED} here already excludes both —
     * there is no separate {@code ACX} state to check.
     */
    public Optional<ProblemLibraryEntry> getNextRecommendedProblem() {
        return selectNextRecommended(getBlindOrderEntries());
    }

    /** Package-visible, DB-free selection logic (mirrors {@code ProblemDashboardService}'s
     *  static-method-over-plain-lists convention) so the mandatory-gating rule is unit testable
     *  directly against a hand-built list, without the shared test database's cross-test noise. */
    static Optional<ProblemLibraryEntry> selectNextRecommended(List<ProblemLibraryEntry> blindOrder) {
        Optional<ProblemLibraryEntry> nextMandatory = blindOrder.stream()
                .filter(entry -> entry.progress().getState() != ProblemState.SOLVED)
                .filter(entry -> entry.roadmapEntry() == null || entry.roadmapEntry().isMandatory())
                .findFirst();
        if (nextMandatory.isPresent()) {
            return nextMandatory;
        }
        return blindOrder.stream()
                .filter(entry -> entry.progress().getState() != ProblemState.SOLVED)
                .findFirst();
    }

    /**
     * Roadmap positions currently flagged {@link ProblemState#NEEDS_REVISIT} ("Could Not Solve" in the
     * Solving Workspace), in Blind Order — a queue to work back through without disturbing the main
     * roadmap sequence or the frontier {@link #getNextRecommendedProblem()} tracks (#161).
     */
    public List<ProblemLibraryEntry> getRevisitQueue() {
        return getBlindOrderEntries().stream()
                .filter(entry -> entry.progress().getState() == ProblemState.NEEDS_REVISIT)
                .toList();
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
