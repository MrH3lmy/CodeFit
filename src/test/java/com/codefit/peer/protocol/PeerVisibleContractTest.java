package com.codefit.peer.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.security.Key;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The peer-visible boundary is an explicit allow-list. Walking every record reachable from
 * {@link MessageBody} must find exactly the approved fields, so adding a field to the wire is a
 * deliberate, reviewed change to this list - and fields that would carry raw learner content, imported
 * workbook text, or key material can never slip in unnoticed.
 */
class PeerVisibleContractTest {

    /** Every field a peer may receive in v1, as {@code Record.component}. */
    private static final Set<String> APPROVED = Set.of(
            "IdentityBinding.transportKey", "IdentityBinding.validFrom", "IdentityBinding.validUntil",
            "IdentityKey.bytes",
            "SocialProfileCard.displayName", "SocialProfileCard.bio", "SocialProfileCard.comparisonZoneId",
            "SocialProfileCard.weekStart",
            "ProgressSummary.window", "ProgressSummary.cutoff", "ProgressSummary.metrics",
            "ComparisonWindow.kind", "ComparisonWindow.localStartDate", "ComparisonWindow.zoneId",
            "ComparisonWindow.weekStart", "ComparisonWindow.start", "ComparisonWindow.end",
            "MetricValue.metricId", "MetricValue.metricVersion", "MetricValue.unit", "MetricValue.availability",
            "MetricValue.value", "MetricValue.sampleSize", "MetricValue.provenance", "MetricValue.timestampBasis",
            "PreparationSnapshot.profileId", "PreparationSnapshot.profileFingerprint", "PreparationSnapshot.scoringVersion",
            "PreparationSnapshot.overallThresholdPercent", "PreparationSnapshot.capturedAt",
            "PreparationSnapshot.overallPercent", "PreparationSnapshot.coveragePercent", "PreparationSnapshot.status",
            "PreparationSnapshot.domains",
            "DomainSnapshot.domainId", "DomainSnapshot.weightPercent", "DomainSnapshot.criticalGate",
            "DomainSnapshot.thresholdPercent", "DomainSnapshot.scorePercent", "DomainSnapshot.coveragePercent",
            "DomainSnapshot.measuredRequirementCount", "DomainSnapshot.totalRequirementCount", "DomainSnapshot.status",
            "ConsentRevision.scopes",
            "Tombstone.targetType", "Tombstone.reason", "Tombstone.requestCacheDeletion");

    /** Name fragments that describe content the source-attribution policy keeps local. */
    private static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "answer", "solution", "code", "source", "note", "statement", "prompt", "front", "back", "url",
            "link", "resource", "editorial", "workbook", "title", "description", "token", "secret", "private",
            "password", "seed", "email", "phone", "address", "ip", "location", "avatar", "database", "sql", "row");

    @Test
    void reachableWireFieldsAreExactlyTheApprovedSet() {
        assertEquals(new TreeSet<>(APPROVED), new TreeSet<>(reachableComponents()));
    }

    @Test
    void noWireFieldNameSuggestsRawContentOrSecrets() {
        for (String field : reachableComponents()) {
            String component = field.substring(field.indexOf('.') + 1).toLowerCase(Locale.ROOT);
            for (String fragment : FORBIDDEN_FRAGMENTS) {
                // Short fragments ("ip", "url", "row") only match as a prefix to avoid false hits inside words.
                boolean hit = component.equals(fragment) || component.startsWith(fragment)
                        || component.contains(fragment) && fragment.length() > 3;
                assertFalse(hit, field + " looks like forbidden content: " + fragment);
            }
        }
    }

    @Test
    void noWireTypeCanHoldKeyMaterialOrArbitraryObjects() {
        for (Class<?> type : reachableTypes()) {
            assertFalse(Key.class.isAssignableFrom(type), type + " is key material");
            assertFalse(type == Object.class || java.io.Serializable.class.equals(type), type + " is an open type");
        }
    }

    @Test
    void socialProfileIsDistinctFromInterviewPreparationProfile() {
        for (Class<?> type : reachableTypes()) {
            assertFalse(type.getName().startsWith("com.codefit.model."), "wire type leaks domain model " + type);
            assertFalse(type.getName().startsWith("com.codefit.service."), "wire type leaks service type " + type);
        }
        assertTrue(Set.of(SocialProfileCard.class.getRecordComponents()).stream()
                .noneMatch(c -> c.getName().toLowerCase(Locale.ROOT).contains("interview")));
    }

    private static Set<String> reachableComponents() {
        Set<String> names = new HashSet<>();
        for (Class<?> type : reachableTypes()) {
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    names.add(type.getSimpleName() + "." + component.getName());
                }
            }
        }
        return names;
    }

    private static Set<Class<?>> reachableTypes() {
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(Set.of(MessageBody.class.getPermittedSubclasses()));
        while (!queue.isEmpty()) {
            Class<?> type = queue.pop();
            if (!seen.add(type) || !type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                enqueue(component.getGenericType(), queue);
            }
        }
        return seen;
    }

    private static void enqueue(Type type, Deque<Class<?>> queue) {
        if (type instanceof Class<?> cls) {
            queue.add(cls.isArray() ? cls.getComponentType() : cls);
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                enqueue(argument, queue);
            }
        }
    }
}
