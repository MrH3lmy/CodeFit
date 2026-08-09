package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewProfileServiceTest {

    private final InterviewProfileService interviewProfileService = new InterviewProfileService();

    @Test
    void revolutJavaProfileIsRegistered() {
        List<InterviewPreparationProfile> profiles = interviewProfileService.getProfiles();

        assertEquals(1, profiles.size());
        assertTrue(profiles.stream().anyMatch(profile -> profile.getId().equals(RevolutJavaInterviewProfile.ID)));
        assertEquals(RevolutJavaInterviewProfile.ID, interviewProfileService.getRevolutJavaProfile().getId());
    }

    @Test
    void revolutJavaProfileHasExactlyEightDomains() {
        assertEquals(8, interviewProfileService.getRevolutJavaProfile().getDomains().size());
    }

    @Test
    void revolutJavaProfileDomainIdsAreUnique() {
        List<InterviewDomain> domains = interviewProfileService.getRevolutJavaProfile().getDomains();
        Set<String> distinctIds = domains.stream().map(InterviewDomain::getId).collect(Collectors.toSet());

        assertEquals(domains.size(), distinctIds.size());
    }

    @Test
    void revolutJavaProfileWeightsSumToExactlyOneHundredPercent() {
        int totalWeightPercent = interviewProfileService.getRevolutJavaProfile().getDomains().stream()
                .mapToInt(InterviewDomain::getWeightPercent)
                .sum();

        assertEquals(100, totalWeightPercent);
    }

    @Test
    void revolutJavaProfileIndividualDomainWeightsMatchThePublishedPlan() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();

        assertEquals(18, weightOf(profile, "java-concurrency-jmm"));
        assertEquals(17, weightOf(profile, "live-java-coding-dsa-testing"));
        assertEquals(15, weightOf(profile, "databases-postgresql-jooq"));
        assertEquals(15, weightOf(profile, "distributed-systems-architecture"));
        assertEquals(15, weightOf(profile, "system-design"));
        assertEquals(8, weightOf(profile, "ddd-cqrs-event-driven"));
        assertEquals(6, weightOf(profile, "reliability-observability-jvm-role-stack"));
        assertEquals(6, weightOf(profile, "team-fit-communication-star"));
    }

    private int weightOf(InterviewPreparationProfile profile, String domainId) {
        return profile.findDomainById(domainId).orElseThrow().getWeightPercent();
    }

    @Test
    void exactlyTheFourPublishedGatesAreMarkedCritical() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();
        Set<String> criticalDomainIds = profile.getDomains().stream()
                .filter(InterviewDomain::isCriticalGate)
                .map(InterviewDomain::getId)
                .collect(Collectors.toSet());

        assertEquals(Set.of("java-concurrency-jmm", "live-java-coding-dsa-testing", "databases-postgresql-jooq",
                "system-design"), criticalDomainIds);
    }

    @Test
    void criticalDomainsAllHaveASeventyPercentMinimumReadinessThreshold() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();

        for (InterviewDomain domain : profile.getDomains()) {
            if (domain.isCriticalGate()) {
                assertTrue(domain.hasMinimumReadinessThreshold(),
                        () -> "critical domain " + domain.getId() + " should declare a minimum readiness threshold");
                assertEquals(70, domain.getMinimumReadinessThresholdPercent());
            }
        }
    }

    @Test
    void nonCriticalDomainsDeclareNoMinimumReadinessThreshold() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();

        for (InterviewDomain domain : profile.getDomains()) {
            if (!domain.isCriticalGate()) {
                assertFalse(domain.hasMinimumReadinessThreshold(),
                        () -> "non-critical domain " + domain.getId() + " should not require a minimum readiness threshold");
                assertEquals(null, domain.getMinimumReadinessThresholdPercent());
            }
        }
    }

    @Test
    void concurrencyDomainReferencesAllFourExistingJcipDecksByName() {
        InterviewDomain concurrency = interviewProfileService.getRevolutJavaProfile()
                .findDomainById("java-concurrency-jmm").orElseThrow();

        List<String> availableReferences = concurrency.getRequirements().stream()
                .filter(InterviewRequirement::isAvailable)
                .map(InterviewRequirement::getReference)
                .toList();

        assertEquals(List.of("JCIP 01 - Fundamentals", "JCIP 02 - Task Execution & Cancellation",
                "JCIP 03 - Liveness, Performance & Testing", "JCIP 04 - Locks, Atomics & Memory Model"),
                availableReferences);
    }

    @Test
    void databasesDomainReferencesExistingAbeDecksByName() {
        InterviewDomain databases = interviewProfileService.getRevolutJavaProfile()
                .findDomainById("databases-postgresql-jooq").orElseThrow();

        Set<String> availableReferences = databases.getRequirements().stream()
                .filter(InterviewRequirement::isAvailable)
                .map(InterviewRequirement::getReference)
                .collect(Collectors.toSet());

        assertEquals(Set.of("ABE 02 - Database Transactions, Locking & Isolation",
                "ABE 03 - Idempotency & Race-Condition Prevention"), availableReferences);
    }

    @Test
    void distributedSystemsDomainReferencesExistingAbeAndDatabaseInternalsDecks() {
        InterviewDomain distributed = interviewProfileService.getRevolutJavaProfile()
                .findDomainById("distributed-systems-architecture").orElseThrow();

        Set<String> availableReferences = distributed.getRequirements().stream()
                .filter(InterviewRequirement::isAvailable)
                .map(InterviewRequirement::getReference)
                .collect(Collectors.toSet());

        assertEquals(Set.of("ABE 04 - Kafka Delivery Semantics, Outbox & DLQs",
                "ABE 05 - Distributed Transactions & Sagas",
                "DI 04 - Distributed Foundations & Consistency",
                "DI 05 - Anti-Entropy, Transactions & Consensus"), availableReferences);
    }

    @Test
    void reliabilityDomainReferencesExistingAbeDecks() {
        InterviewDomain reliability = interviewProfileService.getRevolutJavaProfile()
                .findDomainById("reliability-observability-jvm-role-stack").orElseThrow();

        Set<String> availableReferences = reliability.getRequirements().stream()
                .filter(InterviewRequirement::isAvailable)
                .map(InterviewRequirement::getReference)
                .collect(Collectors.toSet());

        assertEquals(Set.of("ABE 07 - Caching, Consistency & Invalidation",
                "ABE 08 - Observability & Production Debugging",
                "ABE 09 - JVM Memory, Garbage Collection & Performance",
                "ABE 10 - API & Database Failure Scenarios"), availableReferences);
    }

    @Test
    void futureRjRequirementsExistWithoutAnyBackingDeckYet() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();

        List<InterviewRequirement> plannedRequirements = profile.getDomains().stream()
                .flatMap(domain -> domain.getRequirements().stream())
                .filter(requirement -> !requirement.isAvailable())
                .toList();

        Set<String> plannedIds = plannedRequirements.stream().map(InterviewRequirement::getId).collect(Collectors.toSet());
        assertEquals(Set.of("rj-01", "rj-02", "rj-03", "rj-04", "rj-05", "rj-06", "rj-07"), plannedIds);
        for (InterviewRequirement requirement : plannedRequirements) {
            assertEquals(null, requirement.getReference(), requirement.getId() + " has no deck yet, so no reference");
        }

        InterviewDomain systemDesign = profile.findDomainById("system-design").orElseThrow();
        assertTrue(systemDesign.getRequirements().stream().noneMatch(InterviewRequirement::isAvailable),
                "System Design has no existing CodeFit deck yet - it is entirely future RJ material");
    }

    @Test
    void revolutJavaProfileIsStructurallyValid() {
        InterviewPreparationProfile profile = interviewProfileService.getRevolutJavaProfile();

        assertTrue(profile.isValid(), () -> "expected no violations but found: " + profile.validate());
        assertEquals(List.of(), interviewProfileService.validateProfile(profile));
    }

    @Test
    void findProfileByIdWorks() {
        Optional<InterviewPreparationProfile> found = interviewProfileService.findProfile(RevolutJavaInterviewProfile.ID);

        assertTrue(found.isPresent());
        assertEquals("Revolut - Java Senior Software Engineer", found.get().getTitle());
    }

    @Test
    void findProfileByUnknownIdReturnsEmptyRatherThanThrowing() {
        assertEquals(Optional.empty(), interviewProfileService.findProfile("does-not-exist"));
    }
}
