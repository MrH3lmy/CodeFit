package com.codefit.desktop;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.*;

/** One disposable live writer; source-profile selection is deliberately absent. */
public final class PracticeApplicationMain {
    private PracticeApplicationMain() {}

    public static void main(String[] args) {
        if (Runtime.version().feature() != 21
                || args.length != 4
                || !args[0].equals("--parent")
                || !args[2].equals("--experimental-profile")) {
            System.exit(2);
            return;
        }
        var watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var t = new Thread(r, "practice-parent-watchdog");
                            t.setDaemon(true);
                            return t;
                        });
        boolean ready = false;
        try {
            var parent = ProcessHandle.current().parent().orElseThrow();
            if (parent.pid() != Long.parseLong(args[1]) || !parent.isAlive())
                throw new IllegalArgumentException();
            watchdog.scheduleWithFixedDelay(
                    () -> {
                        if (!parent.isAlive()) Runtime.getRuntime().halt(0);
                    },
                    200,
                    200,
                    TimeUnit.MILLISECONDS);
            try (var profile = ExperimentalProfile.openLearning(Path.of(args[3]))) {
                var service = new PracticeApplicationService(profile, Clock.systemUTC());
                var drafts = new PracticeDraftService(profile, Clock.systemUTC());
                var evaluations = new PracticeEvaluationService(profile, Clock.systemUTC());
                try (evaluations) {
                    ApplicationProtocol.write(
                            System.out,
                            PracticeProtocol.message(
                                    g -> {
                                        g.writeStringField("type", "ready");
                                        g.writeStringField("profileId", service.profileId());
                                        g.writeStringField("sessionId", service.session());
                                    }));
                    ready = true;
                    byte[] frame;
                    while ((frame = ApplicationProtocol.readFrame(System.in, 524288)) != null) {
                        var request = PracticeProtocol.parse(frame);
                        byte[] response;
                        try {
                            service.authorize(request.profile(), request.session());
                            var evaluation =
                                    switch (request.method()) {
                                        case "practice.submission.prepare" ->
                                                evaluations.prepare(
                                                        request.command(),
                                                        request.presentation(),
                                                        request.revision(),
                                                        request.assistance());
                                        case "practice.submission.status" ->
                                                evaluations.status(
                                                        request.command(), request.presentation());
                                        case "practice.submission.resume" ->
                                                evaluations.resume(
                                                        request.command(), request.presentation());
                                        case "practice.submission.cancel" ->
                                                evaluations.cancel(
                                                        request.command(), request.presentation());
                                        default -> null;
                                    };
                            var debrief =
                                    request.method().equals("practice.submission.debrief")
                                            ? evaluations.debrief(
                                                    request.command(), request.presentation())
                                            : null;
                            var submissions =
                                    request.method().equals("practice.submissions")
                                            ? evaluations.recent(request.presentation())
                                            : null;
                            var resumed =
                                    request.method().equals("practice.presentation.resume")
                                            ? drafts.resume(request.presentation())
                                            : null;
                            var draft =
                                    resumed != null
                                            ? resumed.draft()
                                            : request.method().equals("practice.draft.read")
                                                    ? drafts.read(request.presentation())
                                                    : null;
                            var recent =
                                    request.method().equals("practice.presentations")
                                            ? drafts.recent()
                                            : null;
                            var saved =
                                    request.method().equals("practice.draft.save")
                                            ? drafts.save(
                                                    request.command(),
                                                    request.presentation(),
                                                    request.revision(),
                                                    request.source(),
                                                    request.reflection())
                                            : request.method().equals("practice.draft.status")
                                                    ? drafts.status(
                                                            request.command(),
                                                            request.presentation())
                                                    : null;
                            var candidates =
                                    request.method().equals("practice.list")
                                            ? service.list()
                                            : null;
                            var opened =
                                    request.method().equals("practice.open")
                                            ? service.open(
                                                    request.command(),
                                                    request.quest(),
                                                    request.challenge())
                                            : null;
                            var receipt =
                                    opened != null
                                            ? opened.receipt()
                                            : request.method().equals("practice.status")
                                                    ? service.status(request.command())
                                                    : null;
                            response =
                                    PracticeProtocol.message(
                                            g -> {
                                                g.writeStringField("id", request.id());
                                                g.writeBooleanField("ok", true);
                                                g.writeStringField(
                                                        "profileId", service.profileId());
                                                g.writeStringField("sessionId", service.session());
                                                g.writeStringField("method", request.method());
                                                if (request.method()
                                                        .startsWith("practice.submission")) {
                                                    g.writeStringField(
                                                            "presentationId",
                                                            request.presentation());
                                                    if (submissions != null) {
                                                        g.writeArrayFieldStart("submissions");
                                                        for (var r : submissions)
                                                            PracticeProtocol.evaluation(g, r);
                                                        g.writeEndArray();
                                                    } else {
                                                        g.writeStringField(
                                                                "commandId", request.command());
                                                        if (debrief != null)
                                                            PracticeDebriefProtocol.write(
                                                                    g, debrief);
                                                        else {
                                                            g.writeFieldName("evaluation");
                                                            PracticeProtocol.evaluation(
                                                                    g, evaluation);
                                                        }
                                                    }
                                                } else if (recent != null) {
                                                    g.writeArrayFieldStart("presentations");
                                                    for (var r : recent)
                                                        PracticeProtocol.receipt(g, r);
                                                    g.writeEndArray();
                                                } else if (draft != null) {
                                                    g.writeStringField(
                                                            "presentationId",
                                                            request.presentation());
                                                    PracticeProtocol.draft(g, draft);
                                                    if (resumed != null) {
                                                        g.writeFieldName("receipt");
                                                        PracticeProtocol.receipt(
                                                                g, resumed.receipt());
                                                        g.writeStringField(
                                                                "instructions",
                                                                resumed.instructions());
                                                    }
                                                } else if (request.method()
                                                        .startsWith("practice.draft.")) {
                                                    g.writeStringField(
                                                            "commandId", request.command());
                                                    g.writeStringField(
                                                            "presentationId",
                                                            request.presentation());
                                                    PracticeProtocol.saved(g, saved);
                                                } else if (candidates != null) {
                                                    g.writeArrayFieldStart("candidates");
                                                    for (var c : candidates) {
                                                        g.writeStartObject();
                                                        g.writeStringField("questId", c.questId());
                                                        g.writeNumberField(
                                                                "questVersion", c.questVersion());
                                                        g.writeStringField(
                                                                "challengeId", c.challengeId());
                                                        g.writeNumberField(
                                                                "challengeVersion",
                                                                c.challengeVersion());
                                                        g.writeStringField("title", c.title());
                                                        g.writeNumberField(
                                                                "effortMinutes", c.effortMinutes());
                                                        g.writeEndObject();
                                                    }
                                                    g.writeEndArray();
                                                } else {
                                                    g.writeStringField(
                                                            "commandId", request.command());
                                                    g.writeFieldName("receipt");
                                                    PracticeProtocol.receipt(g, receipt);
                                                    if (opened != null)
                                                        g.writeStringField(
                                                                "instructions",
                                                                opened.instructions());
                                                }
                                            });
                        } catch (Exception error) {
                            String code =
                                    Set.of(
                                                            "SESSION_MISMATCH",
                                                            "STALE_REVISION",
                                                            "BUSY",
                                                            "COMMAND_ID_CONFLICT",
                                                            "NOT_ELIGIBLE",
                                                            "NOT_AUTHORIZED",
                                                            "INVALID_INPUT",
                                                            "CONTENT_UNAVAILABLE")
                                                    .contains(
                                                            error.getMessage() == null
                                                                    ? ""
                                                                    : error.getMessage())
                                            ? error.getMessage()
                                            : "OPERATION_UNAVAILABLE";
                            response =
                                    PracticeProtocol.message(
                                            g -> {
                                                g.writeStringField("id", request.id());
                                                g.writeBooleanField("ok", false);
                                                g.writeStringField("code", code);
                                            });
                        }
                        ApplicationProtocol.write(System.out, response);
                    }
                } finally {
                    // Never release the profile lock while a worker might still finalize. A hard
                    // stop preserves the existing durable recovery/fencing contract on restart.
                    if (!evaluations.terminated()) Runtime.getRuntime().halt(3);
                }
            }
        } catch (Exception error) {
            if (!ready)
                try {
                    ApplicationProtocol.write(
                            System.out,
                            PracticeProtocol.message(
                                    g -> {
                                        g.writeStringField("type", "startup-error");
                                        g.writeStringField(
                                                "code",
                                                "PROFILE_BUSY".equals(error.getMessage())
                                                        ? "PROFILE_BUSY"
                                                        : "PROFILE_INVALID");
                                    }));
                } catch (Exception ignored) {
                }
            System.err.println("PRACTICE_SESSION_CLOSED");
        } finally {
            watchdog.shutdownNow();
        }
    }
}
