package com.codefit.desktop;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.util.List;

/** Explicit allowlist; never serializes domain objects or private artifacts generically. */
final class PracticeDebriefProtocol {
    static void write(JsonGenerator g, PracticeDebrief.Projection d) throws IOException {
        g.writeObjectFieldStart("debrief");
        g.writeNumberField("v", d.v());
        g.writeBooleanField("readOnly", true);
        g.writeStringField("readAt", d.readAt());
        g.writeStringField("currentEligibility", "NOT_EVALUATED");
        g.writeStringField("nextAction", "EXPLORATORY_PRACTICE");
        g.writeFieldName("evaluation");
        PracticeProtocol.evaluation(g, d.evaluation());
        g.writeStringField("questId", d.questId());
        g.writeNumberField("questVersion", d.questVersion());
        g.writeStringField("graphId", d.graphId());
        g.writeNumberField("graphVersion", d.graphVersion());
        g.writeStringField("rubricId", d.rubricId());
        g.writeNumberField("rubricVersion", d.rubricVersion());
        g.writeNumberField("evaluationRevision", d.evaluationRevision());
        g.writeBooleanField("corrected", d.corrected());
        g.writeStringField("familyId", d.familyId());
        g.writeStringField("variantId", d.variantId());
        g.writeStringField("cohort", d.cohort());
        g.writeStringField("familiarity", d.familiarity());
        g.writeBooleanField("solutionRevealed", d.solutionRevealed());
        g.writeNumberField("priorFamilySubmissions", d.priorFamilySubmissions());
        g.writeStringField("limitations", d.limitations());
        g.writeArrayFieldStart("checks");
        for (var c : d.checks()) {
            g.writeStartObject();
            g.writeStringField("id", c.id());
            g.writeStringField("kind", c.kind());
            g.writeBooleanField("required", c.required());
            g.writeStringField("status", c.status());
            g.writeStringField("feedback", c.feedback());
            g.writeStringField("limitations", c.limitations());
            g.writeArrayFieldStart("targets");
            for (var t : c.targets()) {
                g.writeStartObject();
                g.writeStringField("skillId", t.skillId());
                g.writeNumberField("skillVersion", t.skillVersion());
                g.writeNumberField("behavior", t.behavior());
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        g.writeEndArray();
        var e = d.evidence();
        g.writeObjectFieldStart("evidence");
        g.writeStringField("status", e.status());
        g.writeStringField("id", e.id());
        number(g, "revision", e.revision());
        g.writeStringField("previousId", e.previousId());
        g.writeStringField("recordedAt", e.recordedAt());
        g.writeStringField("provenance", e.provenance());
        g.writeStringField("outcome", e.outcome());
        g.writeStringField("availability", e.availability());
        g.writeStringField("assistance", e.assistance());
        g.writeStringField("exposure", e.exposure());
        g.writeStringField("change", e.change());
        strings(g, "limitations", e.limitations());
        g.writeStringField("mappingId", e.mappingId());
        number(g, "mappingVersion", e.mappingVersion());
        g.writeStringField("mappingStatus", e.mappingStatus());
        g.writeEndObject();
        g.writeStringField("skillStatus", d.skillStatus());
        g.writeArrayFieldStart("skills");
        for (var s : d.skills()) {
            g.writeStartObject();
            g.writeStringField("id", s.id());
            g.writeNumberField("version", s.version());
            g.writeStringField("name", s.name());
            strings(g, "behaviors", s.behaviors());
            var i = s.estimate();
            g.writeObjectFieldStart("interpretation");
            g.writeStringField("status", i.status());
            g.writeStringField("reason", i.reason());
            number(g, "revision", i.revision());
            g.writeStringField("asOf", i.asOf() == null ? null : i.asOf().toString());
            g.writeStringField("policyVersion", i.policyVersion());
            g.writeStringField("band", i.band());
            g.writeStringField("support", i.support());
            g.writeStringField("freshness", i.freshness());
            g.writeFieldName("contradicted");
            if (i.contradicted() == null) g.writeNull();
            else g.writeBoolean(i.contradicted());
            g.writeNumberField("exclusionCount", i.exclusionCount());
            g.writeStringField("currentSupport", "NOT_EVALUATED");
            g.writeArrayFieldStart("supportedBehaviors");
            for (var b : i.supportedBehaviors()) g.writeNumber(b);
            g.writeEndArray();
            strings(g, "reasons", i.reasons());
            strings(g, "warnings", i.warnings());
            g.writeEndObject();
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    private static void number(JsonGenerator g, String key, Number n) throws IOException {
        g.writeFieldName(key);
        if (n == null) g.writeNull();
        else g.writeNumber(n.longValue());
    }

    private static void strings(JsonGenerator g, String key, List<String> values)
            throws IOException {
        g.writeArrayFieldStart(key);
        for (String v : values) g.writeString(v);
        g.writeEndArray();
    }
}
