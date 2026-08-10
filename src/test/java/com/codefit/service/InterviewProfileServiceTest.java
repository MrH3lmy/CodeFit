package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialReference;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewProfileServiceTest {

    private final InterviewProfileService interviewProfileService = new InterviewProfileService();

    @Test
    void revolutJavaProfileIsRegisteredAndStructurallyValid() {
        List<InterviewPreparationProfile> profiles = interviewProfileService.getProfiles();
        assertEquals(1, profiles.size());
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();
        assertEquals(RevolutJavaInterviewProfile.ID, profile.getId());
        assertTrue(profile.isValid(), () -> "expected no profile violations but found " + profile.validate());
        assertEquals(List.of(), interviewProfileService.validateProfile(profile));
    }

    @Test
    void revolutJavaProfileHasThePublishedEightDomainsAndWeights() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();
        assertEquals(8, profile.getDomains().size());
        Map<String, Integer> expected = Map.of(
                "java-concurrency-jmm", 18,
                "live-java-coding-dsa-testing", 17,
                "databases-postgresql-jooq", 15,
                "distributed-systems-architecture", 15,
                "system-design", 15,
                "ddd-cqrs-event-driven", 8,
                "reliability-observability-jvm-role-stack", 6,
                "team-fit-communication-star", 6);
        assertEquals(expected, profile.getDomains().stream()
                .collect(Collectors.toMap(InterviewDomain::getId, InterviewDomain::getWeightPercent)));
        assertEquals(100, profile.getDomains().stream().mapToInt(InterviewDomain::getWeightPercent).sum());
    }

    @Test
    void exactlyTheFourPublishedGatesAreCriticalAtSeventyPercent() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();
        Set<String> expected = Set.of("java-concurrency-jmm", "live-java-coding-dsa-testing",
                "databases-postgresql-jooq", "system-design");
        Set<String> critical = profile.getDomains().stream().filter(InterviewDomain::isCriticalGate)
                .map(InterviewDomain::getId).collect(Collectors.toSet());
        assertEquals(expected, critical);
        for (InterviewDomain domain : profile.getDomains()) {
            if (domain.isCriticalGate()) {
                assertEquals(70, domain.getMinimumReadinessThresholdPercent());
            } else {
                assertFalse(domain.hasMinimumReadinessThreshold());
            }
        }
    }

    @Test
    void requirementIdsAreUniqueAcrossTheWholeProfile() {
        List<String> ids = interviewProfileService.getRevolutJavaProfile().getDomains().stream()
                .flatMap(domain -> domain.getRequirements().stream())
                .map(InterviewRequirement::getId)
                .toList();
        assertEquals(ids.size(), new LinkedHashSet<>(ids).size());
    }

    @Test
    void liveCodingKeepsProblemSolvingTypedSeparatelyFromDecks() {
        InterviewDomain liveCoding = interviewProfileService.getRevolutJavaProfile()
                .findDomainById("live-java-coding-dsa-testing").orElseThrow();
        assertEquals(InterviewMaterialType.DECK, requirement(liveCoding, "java-be-06-testing").getReference().type());
        assertEquals(InterviewMaterialType.PROBLEM_SOLVING,
                requirement(liveCoding, "problem-solving-system").getReference().type());
        assertEquals(InterviewMaterialType.DECK, requirement(liveCoding, "rj-01").getReference().type());
    }

    @Test
    void allSevenRjRequirementsAreAvailableAndBackedByTheBundledDeckNames() {
        Map<String, String> expected = Map.of(
                "rj-01", RevolutInterviewContentPackService.RJ01_DECK,
                "rj-02", RevolutInterviewContentPackService.RJ02_DECK,
                "rj-03", RevolutInterviewContentPackService.RJ03_DECK,
                "rj-04", RevolutInterviewContentPackService.RJ04_DECK,
                "rj-05", RevolutInterviewContentPackService.RJ05_DECK,
                "rj-06", RevolutInterviewContentPackService.RJ06_DECK,
                "rj-07", RevolutInterviewContentPackService.RJ07_DECK);

        Map<String, InterviewRequirement> byId = interviewProfileService.getRevolutJavaProfile().getDomains().stream()
                .flatMap(domain -> domain.getRequirements().stream())
                .collect(Collectors.toMap(InterviewRequirement::getId, requirement -> requirement));

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            InterviewRequirement requirement = byId.get(entry.getKey());
            assertTrue(requirement.isAvailable(), entry.getKey() + " must now be measurable content, not PLANNED");
            assertEquals(InterviewMaterialType.DECK, requirement.getReference().type());
            assertEquals(entry.getValue(), requirement.getReference().key());
            assertEquals(entry.getValue(), requirement.getTitle());
        }
    }

    @Test
    void systemDesignNowHasTwoRealDeckBackedRequirements() {
        InterviewDomain systemDesign = interviewProfileService.getRevolutJavaProfile()
                .findDomainById("system-design").orElseThrow();
        assertEquals(List.of(RevolutInterviewContentPackService.RJ04_DECK,
                        RevolutInterviewContentPackService.RJ05_DECK),
                systemDesign.getRequirements().stream().map(InterviewRequirement::getReference)
                        .map(InterviewMaterialReference::key).toList());
        assertTrue(systemDesign.getRequirements().stream().allMatch(InterviewRequirement::isAvailable));
    }

    @Test
    void everyDeckReferenceResolvesToKnownTrainingMaterialOrTheInterviewContentPack() {
        Set<String> knownDeckNames = new LinkedHashSet<>();
        new TrainingPathService().getTrainingPaths().stream()
                .flatMap(path -> path.getModules().stream())
                .flatMap(module -> module.getDeckNames().stream())
                .map(String::toLowerCase)
                .forEach(knownDeckNames::add);
        new RevolutInterviewContentPackService().deckNames().stream()
                .map(String::toLowerCase)
                .forEach(knownDeckNames::add);

        List<InterviewMaterialReference> deckReferences = interviewProfileService.getRevolutJavaProfile().getDomains().stream()
                .flatMap(domain -> domain.getRequirements().stream())
                .filter(InterviewRequirement::isAvailable)
                .map(InterviewRequirement::getReference)
                .filter(reference -> reference.type() == InterviewMaterialType.DECK)
                .toList();
        assertFalse(deckReferences.isEmpty());
        for (InterviewMaterialReference reference : deckReferences) {
            assertTrue(knownDeckNames.contains(reference.key().toLowerCase()),
                    () -> "Unknown DECK reference: " + reference.key());
        }
    }

    @Test
    void existingAbeAndDiReferencesRemainIntact() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();
        assertEquals(Set.of("ABE 02 - Database Transactions, Locking & Isolation",
                        "ABE 03 - Idempotency & Race-Condition Prevention",
                        RevolutInterviewContentPackService.RJ02_DECK),
                deckKeys(profile.findDomainById("databases-postgresql-jooq").orElseThrow()));
        assertEquals(Set.of("ABE 04 - Kafka Delivery Semantics, Outbox & DLQs",
                        "ABE 05 - Distributed Transactions & Sagas",
                        "DI 04 - Distributed Foundations & Consistency",
                        "DI 05 - Anti-Entropy, Transactions & Consensus"),
                deckKeys(profile.findDomainById("distributed-systems-architecture").orElseThrow()));
    }

    @Test
    void findProfileByIdAndUnknownIdBehavePredictably() {
        Optional<InterviewPreparationProfile> found = interviewProfileService.findProfile(RevolutJavaInterviewProfile.ID);
        assertTrue(found.isPresent());
        assertEquals("Revolut - Java Senior Software Engineer", found.orElseThrow().getTitle());
        assertEquals(Optional.empty(), interviewProfileService.findProfile("does-not-exist"));
    }

    private InterviewRequirement requirement(InterviewDomain domain, String id) {
        return domain.getRequirements().stream().filter(requirement -> requirement.getId().equals(id))
                .findFirst().orElseThrow();
    }

    private Set<String> deckKeys(InterviewDomain domain) {
        return domain.getRequirements().stream()
                .filter(InterviewRequirement::isAvailable)
                .map(InterviewRequirement::getReference)
                .filter(reference -> reference.type() == InterviewMaterialType.DECK)
                .map(InterviewMaterialReference::key)
                .collect(Collectors.toSet());
    }
}
