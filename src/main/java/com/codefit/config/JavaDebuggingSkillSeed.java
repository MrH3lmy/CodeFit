package com.codefit.config;

import com.codefit.evolution.GraphRef;
import com.codefit.evolution.SkillDefinition;
import com.codefit.evolution.SkillGraph;
import com.codefit.evolution.SkillLifecycle;
import com.codefit.evolution.SkillPrerequisite;
import com.codefit.evolution.SkillRef;
import com.codefit.repository.SkillCatalogRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Original EV-02 taxonomy from docs/evolution/java-debugging-concurrency.md, not training content. */
public final class JavaDebuggingSkillSeed {
    public static final String DOMAIN_ID = "software-engineering";
    public static final GraphRef GRAPH = new GraphRef("java-debugging-concurrency", 1);

    private JavaDebuggingSkillSeed() { }

    public static SkillGraph graph() {
        SkillDefinition reproduce = definition("java.debugging.reproduce", "Reproduce and localize a bug",
                "Converts a failing example/trace into a minimal regression check and correctly localizes the fault");
        SkillDefinition atomic = definition("java.concurrency.atomic-update", "Repair compound atomic updates",
                "Distinguishes visibility from compound atomicity; repairs lost updates and preserves exact totals under the specified checks");
        SkillDefinition invariant = definition("java.concurrency.invariant", "Preserve concurrent invariants",
                "Preserves a multi-field invariant under competing updates; selects an atomic boundary that covers the whole invariant");
        SkillDefinition liveness = definition("java.concurrency.liveness", "Repair lock-order liveness",
                "Identifies a lock-order cycle and repairs termination without removing required safety behavior");
        return new SkillGraph(GRAPH, List.of(reproduce, atomic, invariant, liveness), List.of(
                new SkillPrerequisite(reproduce.ref(), atomic.ref()),
                new SkillPrerequisite(atomic.ref(), invariant.ref()),
                new SkillPrerequisite(invariant.ref(), liveness.ref())));
    }

    static void seed(Connection connection) throws SQLException {
        SkillCatalogRepository repository = new SkillCatalogRepository();
        SkillGraph graph = graph();
        for (SkillDefinition definition : graph.definitions()) repository.publishDefinition(connection, definition);
        repository.publishGraph(connection, graph);
    }

    private static SkillDefinition definition(String id, String name, String behavior) {
        return new SkillDefinition(new SkillRef(id, 1), DOMAIN_ID, name, behavior, List.of(behavior), SkillLifecycle.ACTIVE);
    }
}
