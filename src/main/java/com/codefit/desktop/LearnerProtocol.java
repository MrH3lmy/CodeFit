package com.codefit.desktop;

import com.fasterxml.jackson.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Version 3 finite read capabilities, bound to capture session and source identity. */
final class LearnerProtocol {
    private static final JsonFactory JSON =
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(2)
                                    .maxStringLength(128)
                                    .maxNumberLength(2)
                                    .build())
                    .build();

    record Request(String id, String profileId, String sessionId, String method) {}

    static Request parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 1024) throw new IOException("INVALID_INPUT");
        Map<String, String> fields = new HashMap<>();
        try (var p =
                JSON.createParser(
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(bytes))
                                .toString())) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("INVALID_INPUT");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME)
                    throw new IOException("INVALID_INPUT");
                String name = p.currentName();
                var token = p.nextToken();
                if (name.equals("v")
                        ? token != JsonToken.VALUE_NUMBER_INT
                        : token != JsonToken.VALUE_STRING) throw new IOException("INVALID_INPUT");
                fields.put(name, p.getText());
            }
            if (p.nextToken() != null) throw new IOException("INVALID_INPUT");
        }
        if (!fields.keySet().equals(Set.of("v", "id", "method", "profileId", "sessionId"))
                || !"3".equals(fields.get("v"))
                || !Set.of("learner.overview.read", "learner.skills.read", "learner.missions.read")
                        .contains(fields.get("method"))
                || !ApplicationProtocol.uuid(fields.get("id"))
                || !ApplicationProtocol.uuid(fields.get("sessionId"))
                || !fields.get("profileId").matches("[0-9a-f]{64}"))
            throw new IOException("INVALID_INPUT");
        return new Request(
                fields.get("id"),
                fields.get("profileId"),
                fields.get("sessionId"),
                fields.get("method"));
    }

    static byte[] readFrame(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int b; (b = input.read()) != -1; ) {
            if (b == '\n') return bytes.toByteArray();
            if (bytes.size() >= 1024) throw new IOException("INVALID_INPUT");
            bytes.write(b);
        }
        if (bytes.size() != 0) throw new IOException("INVALID_INPUT");
        return null;
    }

    interface Writer {
        void write(JsonGenerator j) throws IOException;
    }

    private static byte[] frame(Writer writer) throws IOException {
        return frame(writer, 8192);
    }

    private static byte[] frame(Writer writer, int limit) throws IOException {
        var bytes = new ByteArrayOutputStream();
        OutputStream bounded =
                new FilterOutputStream(bytes) {
                    @Override
                    public void write(int b) throws IOException {
                        if (bytes.size() >= limit) throw new IOException("READ_FAILED");
                        out.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        if (len > limit - bytes.size()) throw new IOException("READ_FAILED");
                        out.write(b, off, len);
                    }
                };
        try (var j = JSON.createGenerator(bounded)) {
            j.writeStartObject();
            j.writeNumberField("v", 3);
            writer.write(j);
            j.writeEndObject();
        }
        return bytes.toByteArray();
    }

    static byte[] ready(LearnerSnapshot snapshot) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("type", "ready");
                    j.writeStringField("profileId", snapshot.profileId());
                    j.writeStringField("sessionId", snapshot.sessionId());
                });
    }

    static byte[] startupFailed(String code) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("type", "startup-error");
                    j.writeStringField("code", code);
                });
    }

    static byte[] failed(String id, String code) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", false);
                    j.writeStringField("code", code);
                });
    }

    static byte[] result(String id, LearnerReadProjection.Overview o) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", true);
                    j.writeObjectFieldStart("result");
                    j.writeNumberField("v", 1);
                    j.writeStringField("kind", "learner-overview");
                    j.writeStringField("source", "learner-snapshot");
                    j.writeStringField("profileId", o.profileId());
                    j.writeStringField("sessionId", o.sessionId());
                    j.writeStringField("capturedAt", o.capturedAt().toString());
                    j.writeNumberField("schemaVersion", 25);
                    j.writeBooleanField("readOnly", true);
                    j.writeObjectFieldStart("identity");
                    j.writeStringField(
                            "status", o.displayName() == null ? "unavailable" : "available");
                    j.writeStringField("displayName", o.displayName());
                    j.writeEndObject();
                    j.writeObjectFieldStart("progression");
                    j.writeStringField("status", o.xp() == null ? "unavailable" : "available");
                    if (o.xp() == null) {
                        j.writeNullField("xp");
                        j.writeNullField("level");
                    } else {
                        j.writeNumberField("xp", o.xp());
                        j.writeNumberField("level", o.level());
                    }
                    j.writeEndObject();
                    j.writeObjectFieldStart("activity");
                    j.writeNumberField("reviewCount", o.reviewCount());
                    j.writeNumberField("dueScheduledCards", o.dueScheduledCards());
                    j.writeStringField("reviewDay", o.reviewDay());
                    j.writeEndObject();
                    for (String key : List.of("mastery", "recommendations", "missions")) {
                        j.writeObjectFieldStart(key);
                        j.writeStringField("status", "unavailable");
                        j.writeEndObject();
                    }
                    j.writeEndObject();
                });
    }

    static byte[] result(String id, LearnerSkillProjection.Projection p) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", true);
                    j.writeObjectFieldStart("result");
                    j.writeNumberField("v", 1);
                    j.writeStringField("kind", "learner-skills");
                    j.writeStringField("source", "learner-snapshot");
                    j.writeStringField("profileId", p.profileId());
                    j.writeStringField("sessionId", p.sessionId());
                    j.writeStringField("capturedAt", p.capturedAt().toString());
                    j.writeNumberField("schemaVersion", 25);
                    j.writeBooleanField("readOnly", true);
                    j.writeStringField("status", p.status());
                    j.writeStringField("reason", p.reason());
                    j.writeFieldName("graph");
                    if (p.graph() == null) j.writeNull();
                    else ref(j, p.graph());
                    j.writeBooleanField("newerGraphAvailable", p.newerGraphAvailable());
                    j.writeArrayFieldStart("nodes");
                    for (var node : p.nodes()) {
                        j.writeStartObject();
                        j.writeFieldName("ref");
                        ref(j, node.ref());
                        j.writeStringField("domainId", node.domainId());
                        j.writeStringField("name", node.name());
                        j.writeStringField("description", node.description());
                        strings(j, "behaviors", node.behaviors());
                        j.writeStringField("lifecycle", node.lifecycle());
                        j.writeObjectFieldStart("estimate");
                        var e = node.estimate();
                        j.writeStringField("status", e.status());
                        j.writeStringField("reason", e.reason());
                        number(j, "revision", e.revision());
                        j.writeStringField("asOf", e.asOf() == null ? null : e.asOf().toString());
                        j.writeStringField("policyVersion", e.policyVersion());
                        j.writeStringField("epochId", e.epochId());
                        j.writeStringField("band", e.band());
                        j.writeStringField("historicalBand", e.historicalBand());
                        j.writeStringField("support", e.support());
                        j.writeStringField("freshness", e.freshness());
                        bool(j, "contradicted", e.contradicted());
                        bool(j, "regressionCap", e.regressionCap());
                        j.writeStringField("currentSupport", "NOT_EVALUATED");
                        number(j, "independentFamilies", e.independentFamilies());
                        j.writeArrayFieldStart("supportedBehaviors");
                        for (int b : e.supportedBehaviors()) j.writeNumber(b);
                        j.writeEndArray();
                        j.writeStringField(
                                "lastMeaningfulObservation",
                                e.lastMeaningfulObservation() == null
                                        ? null
                                        : e.lastMeaningfulObservation().toString());
                        j.writeNumberField("contributionCount", e.contributionCount());
                        j.writeNumberField("exclusionCount", e.exclusionCount());
                        counts(j, "provenanceCounts", e.provenanceCounts());
                        counts(j, "assistanceCounts", e.assistanceCounts());
                        counts(j, "exposureCounts", e.exposureCounts());
                        strings(j, "reasons", e.reasons());
                        strings(j, "warnings", e.warnings());
                        j.writeEndObject();
                        j.writeEndObject();
                    }
                    j.writeEndArray();
                    j.writeArrayFieldStart("edges");
                    for (var edge : p.edges()) {
                        j.writeStartObject();
                        j.writeFieldName("prerequisite");
                        ref(j, edge.prerequisite());
                        j.writeFieldName("dependent");
                        ref(j, edge.dependent());
                        j.writeEndObject();
                    }
                    j.writeEndArray();
                    j.writeEndObject();
                },
                256 * 1024);
    }

    static byte[] result(String id, LearnerMissionProjection.Projection p) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", true);
                    j.writeObjectFieldStart("result");
                    j.writeNumberField("v", 1);
                    j.writeStringField("kind", "learner-missions");
                    j.writeStringField("source", "learner-snapshot");
                    j.writeBooleanField("readOnly", true);
                    j.writeStringField("profileId", p.profileId());
                    j.writeStringField("sessionId", p.sessionId());
                    j.writeStringField("capturedAt", p.capturedAt().toString());
                    j.writeNumberField("schemaVersion", 25);
                    j.writeStringField("currentEligibility", "NOT_EVALUATED");
                    j.writeStringField("activeAssignment", "NOT_EVALUATED");
                    j.writeBooleanField("actionsEnabled", false);
                    j.writeStringField("catalogStatus", p.catalogStatus());
                    j.writeArrayFieldStart("quests");
                    for (var q : p.quests()) {
                        j.writeStartObject();
                        j.writeStringField("id", q.id());
                        j.writeNumberField("version", q.version());
                        j.writeStringField("title", q.title());
                        j.writeStringField("cohort", q.cohort());
                        j.writeEndObject();
                    }
                    j.writeEndArray();
                    j.writeStringField("historyStatus", p.historyStatus());
                    j.writeBooleanField("historyTruncated", p.historyTruncated());
                    j.writeArrayFieldStart("activities");
                    for (var a : p.activities()) {
                        j.writeStartObject();
                        j.writeStringField("command", a.command());
                        j.writeStringField("presentation", a.presentation());
                        j.writeStringField("questId", a.questId());
                        j.writeNumberField("questVersion", a.questVersion());
                        j.writeStringField("challengeId", a.challengeId());
                        j.writeNumberField("challengeVersion", a.challengeVersion());
                        j.writeStringField("submittedAt", a.submittedAt().toString());
                        j.writeStringField(
                                "evaluatedAt",
                                a.evaluatedAt() == null ? null : a.evaluatedAt().toString());
                        j.writeStringField("stage", a.stage());
                        j.writeStringField("outcome", a.outcome());
                        number(j, "revision", a.revision());
                        j.writeBooleanField("withdrawn", a.withdrawn());
                        j.writeStringField("metadata", a.metadata());
                        j.writeEndObject();
                    }
                    j.writeEndArray();
                    j.writeStringField("decisionStatus", p.decisionStatus());
                    j.writeBooleanField("decisionsTruncated", p.decisionsTruncated());
                    j.writeArrayFieldStart("decisions");
                    for (var d : p.decisions()) {
                        j.writeStartObject();
                        j.writeStringField("id", d.id());
                        j.writeStringField("asOf", d.asOf().toString());
                        j.writeEndObject();
                    }
                    j.writeEndArray();
                    j.writeObjectFieldStart("review");
                    j.writeNumberField("recordedReviews", p.reviewCount());
                    j.writeNumberField("dueScheduledCards", p.dueScheduledCards());
                    j.writeStringField("asOfDay", p.reviewDay());
                    j.writeEndObject();
                    j.writeEndObject();
                },
                65536);
    }

    private static void ref(JsonGenerator j, LearnerSkillProjection.Ref ref) throws IOException {
        j.writeStartObject();
        j.writeStringField("id", ref.id());
        j.writeNumberField("version", ref.version());
        j.writeEndObject();
    }

    private static void strings(JsonGenerator j, String name, List<String> values)
            throws IOException {
        j.writeArrayFieldStart(name);
        for (String value : values) j.writeString(value);
        j.writeEndArray();
    }

    private static void counts(
            JsonGenerator j, String name, List<LearnerSkillProjection.Count> values)
            throws IOException {
        j.writeArrayFieldStart(name);
        for (var value : values) {
            j.writeStartObject();
            j.writeStringField("code", value.code());
            j.writeNumberField("count", value.count());
            j.writeEndObject();
        }
        j.writeEndArray();
    }

    private static void number(JsonGenerator j, String name, Number value) throws IOException {
        if (value == null) j.writeNullField(name);
        else j.writeNumberField(name, value.longValue());
    }

    private static void bool(JsonGenerator j, String name, Boolean value) throws IOException {
        if (value == null) j.writeNullField(name);
        else j.writeBooleanField(name, value);
    }
}
