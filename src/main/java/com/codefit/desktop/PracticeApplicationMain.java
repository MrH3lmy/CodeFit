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
                while ((frame = ApplicationProtocol.readFrame(System.in)) != null) {
                    var request = PracticeProtocol.parse(frame);
                    byte[] response;
                    try {
                        service.authorize(request.profile(), request.session());
                        var candidates =
                                request.method().equals("practice.list") ? service.list() : null;
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
                                            g.writeStringField("profileId", service.profileId());
                                            g.writeStringField("sessionId", service.session());
                                            g.writeStringField("method", request.method());
                                            if (candidates != null) {
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
                                                g.writeStringField("commandId", request.command());
                                                g.writeFieldName("receipt");
                                                PracticeProtocol.receipt(g, receipt);
                                                if (opened != null)
                                                    g.writeStringField(
                                                            "instructions", opened.instructions());
                                            }
                                        });
                    } catch (Exception error) {
                        String code =
                                Set.of("SESSION_MISMATCH", "COMMAND_ID_CONFLICT", "NOT_ELIGIBLE")
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
