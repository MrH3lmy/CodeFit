package com.codefit.diagnostics;

import com.fasterxml.jackson.core.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Memory-only hot path. Never accepts source, stdin, diagnostics, paths or arbitrary payloads. */
public final class TimeoutEvents {
    private static final int MAX = 512;
    private static final Queue<Map<String, Object>> EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final Map<String, Integer> TRANSFORMED = new ConcurrentHashMap<>();
    private static final AtomicLong OVERHEAD = new AtomicLong();
    private static final Queue<CompletableFuture<?>> EXITS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger ERRORS = new AtomicInteger();
    private static final long ORIGIN = System.nanoTime();
    private static final ThreadLocal<Phase> LOCAL = new ThreadLocal<>();
    private static volatile Phase active;
    private static volatile String invocation = "none", challenge = "none", check = "COMPILE";
    private static int sequence;
    private static ScheduledExecutorService sampler;

    private static final class Phase {
        final int id;
        final String kind, invocation, challenge, check;
        long start, budget;
        volatile Process child;
        volatile boolean cpuObserved;

        Phase(String kind) {
            this.id = ++sequence;
            this.kind = kind;
            this.invocation = TimeoutEvents.invocation;
            this.challenge = TimeoutEvents.challenge;
            this.check = TimeoutEvents.check;
        }
    }

    public static void initialize() {
        sampler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var t = new Thread(r, "test-timeout-cpu-sample");
                            t.setDaemon(true);
                            return t;
                        });
        sampler.scheduleAtFixedRate(
                () -> {
                    try {
                        Phase p = active;
                        if (p != null && p.child != null && !p.cpuObserved) {
                            var cpu = p.child.info().totalCpuDuration();
                            if (cpu.isPresent() && cpu.get().toNanos() > 0) {
                                p.cpuObserved = true;
                                record(
                                        p,
                                        "FIRST_OBSERVED_CHILD_CPU",
                                        "CPU_NONZERO",
                                        cpu.get().toNanos());
                            }
                        }
                    } catch (Throwable unavailable) {
                        ERRORS.incrementAndGet();
                    }
                },
                100,
                100,
                TimeUnit.MILLISECONDS);
    }

    public static void instrumented(String name, int anchors) {
        TRANSFORMED.put(name, anchors);
    }

    public static void instrumentationFailed() {
        ERRORS.incrementAndGet();
    }

    public static void requireInstrumentation() {
        if (TRANSFORMED.size() != 3 || ERRORS.get() != 0)
            throw new IllegalStateException("Diagnostic anchors unavailable: " + TRANSFORMED);
    }

    public static void submission(String ordinal, String publishedChallenge) {
        invocation = identity(ordinal);
        challenge = identity(publishedChallenge);
        check = "COMPILE";
        record(null, "SUBMISSION_BEGIN", "REQUEST", 0);
        environment("SUBMISSION_ENVIRONMENT");
    }

    public static void check(String publishedId) {
        check = identity(publishedId);
    }

    public static void begin(String kind) {
        long t = System.nanoTime();
        var p = new Phase(kind);
        LOCAL.set(p);
        active = p;
        record(p, "REQUEST", kind, 0);
        OVERHEAD.addAndGet(System.nanoTime() - t);
        environment("PHASE_ENVIRONMENT");
    }

    public static void deadline(long start, int seconds) {
        long t = System.nanoTime();
        var p = LOCAL.get();
        p.start = start;
        p.budget = TimeUnit.SECONDS.toNanos(seconds);
        record(p, "DEADLINE_STARTED", "", 0);
        OVERHEAD.addAndGet(System.nanoTime() - t);
    }

    public static void spawned(Process child) {
        long t = System.nanoTime();
        var p = LOCAL.get();
        p.child = child;
        record(p, "SPAWN_RETURNED", "", 0);
        EXITS.add(child.onExit().thenRun(() -> record(p, "CHILD_EXIT_OBSERVED", "EXITED", 0)));
        OVERHEAD.addAndGet(System.nanoTime() - t);
    }

    public static void registered(Process child) {
        long t = System.nanoTime();
        var p = LOCAL.get();
        if (p.child != child) ERRORS.incrementAndGet();
        record(p, "REGISTRATION_RETURNED", "", 0);
        OVERHEAD.addAndGet(System.nanoTime() - t);
    }

    public static void waiting() {
        long t = System.nanoTime();
        record(LOCAL.get(), "WAIT_ENTER", "", 0);
        OVERHEAD.addAndGet(System.nanoTime() - t);
    }

    public static void waited(boolean finished) {
        long t = System.nanoTime();
        record(LOCAL.get(), "WAIT_RETURNED", finished ? "WITHIN_BUDGET" : "DEADLINE_EXHAUSTED", 0);
        OVERHEAD.addAndGet(System.nanoTime() - t);
    }

    public static void terminating(Process child) {
        long t = System.nanoTime();
        Phase p = LOCAL.get();
        Phase current = active;
        if (p == null && current != null && current.child == child) p = current;
        record(p, "TERMINATION_REQUESTED", "", 0);
        OVERHEAD.addAndGet(System.nanoTime() - t);
    }

    public static void end(Object result) {
        long t = System.nanoTime();
        var p = LOCAL.get();
        try {
            String status;
            if (p.kind.equals("COMPILE"))
                status = ((Enum<?>) result.getClass().getMethod("status").invoke(result)).name();
            else
                status =
                        (boolean) result.getClass().getMethod("timedOut").invoke(result)
                                ? "TIMED_OUT"
                                : (boolean) result.getClass().getMethod("cancelled").invoke(result)
                                        ? "CANCELLED"
                                        : "EXITED";
            record(p, "RETURNED", status, 0);
        } catch (ReflectiveOperationException failure) {
            ERRORS.incrementAndGet();
        } finally {
            active = null;
            LOCAL.remove();
            OVERHEAD.addAndGet(System.nanoTime() - t);
            environment("PHASE_END_ENVIRONMENT");
        }
    }

    public static void finalized(
            String outcome, String evidenceOutcome, boolean gateActive, boolean replayEqual) {
        record(null, "FINALIZED", identity(outcome), 0);
        add(
                Map.of(
                        "event",
                        "INTEGRITY",
                        "invocation",
                        invocation,
                        "evidenceOutcome",
                        identity(evidenceOutcome),
                        "gateActive",
                        gateActive,
                        "replayEqual",
                        replayEqual));
        environment("FINAL_ENVIRONMENT");
    }

    private static void record(Phase p, String event, String status, long childCpuNanos) {
        long now = System.nanoTime();
        var m = new LinkedHashMap<String, Object>();
        m.put("event", event);
        m.put("atNanos", now - ORIGIN);
        m.put("utc", Instant.now().toString());
        m.put("invocation", p == null ? invocation : p.invocation);
        m.put("challenge", p == null ? challenge : p.challenge);
        m.put("phase", p == null ? "NONE" : p.kind);
        m.put("phaseId", p == null ? 0 : p.id);
        m.put("check", p == null ? check : p.check);
        m.put("status", status);
        m.put("remainingNanos", p == null || p.start == 0 ? 0 : p.budget - (now - p.start));
        m.put("budgetNanos", p == null ? 0 : p.budget);
        m.put("childAlive", p != null && p.child != null && p.child.isAlive());
        m.put("childCpuNanos", childCpuNanos);
        add(m);
    }

    private static void add(Map<String, Object> event) {
        if (SIZE.incrementAndGet() <= MAX) EVENTS.add(event);
    }

    private static String identity(String s) {
        if (s == null || s.length() > 120 || !s.matches("[A-Za-z0-9_.@-]+"))
            throw new IllegalArgumentException("Diagnostic identity");
        return s;
    }

    private static void environment(String event) {
        try {
            var m = new LinkedHashMap<String, Object>();
            m.put("event", event);
            m.put("invocation", invocation);
            m.put("atNanos", System.nanoTime() - ORIGIN);
            m.put("cpuQuota", Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).strip());
            for (String line : Files.readAllLines(Path.of("/sys/fs/cgroup/cpu.stat"))) {
                String[] fields = line.split(" ");
                if (fields[0].equals("throttled_usec") || fields[0].equals("nr_throttled"))
                    m.put(fields[0], Long.parseLong(fields[1]));
            }
            m.put(
                    "jvmProcesses",
                    ProcessHandle.allProcesses()
                            .filter(
                                    h ->
                                            h.info()
                                                    .command()
                                                    .map(
                                                            c ->
                                                                    c.endsWith("/java")
                                                                            || c.endsWith("/javac"))
                                                    .orElse(false))
                            .count());
            add(m);
        } catch (IOException unavailable) {
            add(Map.of("event", event, "available", false));
        }
    }

    public static void dump(Path path, int exit) throws IOException {
        try {
            CompletableFuture.allOf(EXITS.toArray(CompletableFuture[]::new))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception unavailable) {
            ERRORS.incrementAndGet();
        }
        sampler.shutdownNow();
        try (var out =
                new JsonFactory()
                        .createGenerator(
                                Files.newOutputStream(
                                        path,
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS))) {
            out.writeStartObject();
            out.writeNumberField("schemaVersion", 1);
            out.writeNumberField("scenarioExit", exit);
            out.writeNumberField("synchronousObserverNanos", OVERHEAD.get());
            out.writeNumberField("observerErrors", ERRORS.get());
            out.writeBooleanField("truncated", SIZE.get() > MAX);
            out.writeObjectFieldStart("anchors");
            for (var e : new TreeMap<>(TRANSFORMED).entrySet())
                out.writeNumberField(e.getKey(), e.getValue());
            out.writeEndObject();
            out.writeArrayFieldStart("events");
            for (var m : EVENTS) {
                out.writeStartObject();
                for (var e : m.entrySet()) {
                    if (e.getValue() instanceof Number n)
                        out.writeNumberField(e.getKey(), n.longValue());
                    else if (e.getValue() instanceof Boolean b)
                        out.writeBooleanField(e.getKey(), b);
                    else out.writeStringField(e.getKey(), (String) e.getValue());
                }
                out.writeEndObject();
            }
            out.writeEndArray();
            out.writeEndObject();
        }
        if (ERRORS.get() != 0 || SIZE.get() > MAX)
            throw new IllegalStateException("Invalid diagnostic capture");
    }
}
