package com.codefit.desktop;

import com.codefit.evolution.GraphRef;
import com.codefit.repository.GraphSelectionRepository.Receipt;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;

final class PracticeGraphProtocol {
    static void write(
            JsonGenerator g,
            PracticeProtocol.Request request,
            PracticeGraphService.View view,
            Receipt receipt)
            throws IOException {
        g.writeStringField("commandId", request.command() == null ? "" : request.command());
        g.writeStringField(
                "originSessionId", request.originSession() == null ? "" : request.originSession());
        g.writeNullField("pending");
        g.writeFieldName("receipt");
        if (receipt == null) g.writeNull();
        else {
            g.writeStartObject();
            g.writeStringField("commandId", receipt.command().id());
            g.writeStringField("originSessionId", receipt.command().origin());
            g.writeFieldName("target");
            ref(g, receipt.command().target());
            g.writeNumberField("expectedRevision", receipt.command().expected());
            g.writeStringField("state", receipt.state());
            g.writeStringField("reason", receipt.reason());
            g.writeFieldName("selected");
            ref(g, receipt.selected());
            g.writeNumberField("revision", receipt.revision());
            g.writeStringField("recordedAt", receipt.at().toString());
            g.writeEndObject();
        }
        g.writeFieldName("view");
        if (view == null) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        g.writeStringField("capturedAt", view.capturedAt().toString());
        g.writeStringField("track", view.selection().track());
        g.writeFieldName("selected");
        ref(g, view.selection().graph());
        g.writeNumberField("revision", view.selection().revision());
        g.writeStringField("selectionStatus", view.selectionStatus());
        g.writeArrayFieldStart("graphs");
        for (var s : view.graphs()) {
            g.writeStartObject();
            g.writeStringField("graphId", s.ref().graphId());
            g.writeNumberField("graphVersion", s.ref().graphVersion());
            g.writeStringField("domain", s.domain());
            g.writeNumberField("skillCount", s.skills());
            g.writeStringField("lifecycle", s.lifecycle());
            g.writeNumberField("lifecycleRevision", s.lifecycleRevision());
            g.writeFieldName("replacement");
            ref(g, s.replacement());
            g.writeStringField("selectionStatus", s.selectable());
            g.writeStringField("mappingStatus", s.compatibility().mapping());
            g.writeNumberField("mappingCount", s.compatibility().mappingCount());
            g.writeStringField("estimator", s.compatibility().estimator());
            g.writeStringField("contentReview", "NOT_RECORDED");
            g.writeStringField(
                    "practice",
                    s.compatibility().estimator().equals("SUPPORTED_PILOT")
                            ? "SUPPORTED_PILOT"
                            : "UNAVAILABLE");
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeFieldName("inspected");
        ref(g, view.inspected());
        g.writeFieldName("detail");
        if (view.detail() == null) g.writeNull();
        else {
            g.writeStartObject();
            g.writeArrayFieldStart("skills");
            for (var d : view.detail().topologicalOrder()) {
                g.writeStartObject();
                g.writeStringField("id", d.ref().skillId());
                g.writeNumberField("version", d.ref().definitionVersion());
                g.writeStringField("domain", d.domainId());
                g.writeStringField("name", d.name());
                g.writeStringField("description", d.description());
                g.writeStringField("lifecycle", d.lifecycle().name());
                g.writeArrayFieldStart("behaviors");
                for (var b : d.observableBehaviors()) g.writeString(b);
                g.writeEndArray();
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeArrayFieldStart("edges");
            for (var e : view.detail().prerequisites()) {
                g.writeStartObject();
                g.writeStringField("from", e.prerequisite().skillId());
                g.writeNumberField("fromVersion", e.prerequisite().definitionVersion());
                g.writeStringField("to", e.dependent().skillId());
                g.writeNumberField("toVersion", e.dependent().definitionVersion());
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        g.writeEndObject();
    }

    private static void ref(JsonGenerator g, GraphRef r) throws IOException {
        if (r == null) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        g.writeStringField("graphId", r.graphId());
        g.writeNumberField("graphVersion", r.graphVersion());
        g.writeEndObject();
    }
}
