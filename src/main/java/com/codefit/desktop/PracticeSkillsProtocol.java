package com.codefit.desktop;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.mastery.OrdinalMasteryPolicy;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;

/** Exact public-safe summary; no generic domain or evidence serialization. */
final class PracticeSkillsProtocol {
    static void write(JsonGenerator g, PracticeSkillsService.Result r) throws IOException {
        g.writeStringField("graphId", JavaDebuggingSkillSeed.GRAPH.graphId());
        g.writeNumberField("graphVersion", JavaDebuggingSkillSeed.GRAPH.graphVersion());
        g.writeStringField("policy", OrdinalMasteryPolicy.VERSION);
        g.writeArrayFieldStart("catalog");
        for (var s : JavaDebuggingSkillSeed.graph().definitions()) {
            g.writeStartObject();
            g.writeStringField("id", s.ref().skillId());
            g.writeNumberField("version", s.ref().definitionVersion());
            g.writeStringField("name", s.name());
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeFieldName("reconciliation");
        if (r == null) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        g.writeStringField("commandId", r.commandId());
        g.writeStringField("originSessionId", r.originSessionId());
        g.writeStringField("state", r.state());
        g.writeStringField("reason", r.reason());
        g.writeStringField("asOf", r.asOf());
        g.writeStringField("policy", r.policy());
        g.writeStringField("inputSha", r.inputSha());
        g.writeArrayFieldStart("skills");
        for (var s : r.skills()) {
            var e = s.estimate();
            g.writeStartObject();
            g.writeStringField("id", s.skillId());
            g.writeNumberField("version", s.skillVersion());
            g.writeStringField("name", s.name());
            g.writeNumberField("revision", s.revision());
            g.writeStringField("inputSha", s.inputSha());
            g.writeBooleanField("created", s.created());
            g.writeStringField("epochId", e.epoch().id());
            g.writeStringField("band", e.band().name());
            g.writeStringField("support", e.support().name());
            g.writeStringField("freshness", e.freshness().name());
            g.writeBooleanField("contradicted", e.contradicted());
            g.writeBooleanField("regressionCap", e.regressionCap());
            g.writeNumberField("independentFamilies", e.independentFamilies());
            g.writeNumberField("exclusionCount", e.exclusions().size());
            g.writeArrayFieldStart("behaviors");
            for (var b : e.supportedBehaviors().stream().sorted().toList()) g.writeNumber(b);
            g.writeEndArray();
            g.writeArrayFieldStart("reasons");
            for (var reason : e.reasons().stream().map(x -> x.message()).distinct().toList())
                g.writeString(reason);
            g.writeEndArray();
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeEndObject();
    }
}
