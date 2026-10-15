package com.codefit.evolution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SkillGraphTest {
    private static SkillDefinition skill(String id) {
        return new SkillDefinition(new SkillRef(id, 1), "example-domain", id, "A measurable capability",
                List.of("Demonstrates " + id + " on an observable task"), SkillLifecycle.ACTIVE);
    }

    private static SkillPrerequisite edge(SkillDefinition from, SkillDefinition to) {
        return new SkillPrerequisite(from.ref(), to.ref());
    }

    @Test
    void chainQueriesDistinguishDirectAndTransitivePrerequisites() {
        var a = skill("test.a"); var b = skill("test.b"); var c = skill("test.c");
        var graph = new SkillGraph(new GraphRef("test.chain", 1), List.of(c, a, b), List.of(edge(b, c), edge(a, b)));
        assertEquals(List.of(a, b, c), graph.topologicalOrder());
        assertEquals(List.of(b), graph.prerequisitesOf(c.ref()));
        assertEquals(List.of(b), graph.dependentsOf(a.ref()));
        assertEquals(List.of(a, b), graph.prerequisiteTraversal(c.ref()));
        assertEquals(List.of(), graph.prerequisiteTraversal(a.ref()));
        assertEquals(List.of(), graph.dependentsOf(c.ref()));
    }

    @Test
    void diamondAndDisconnectedTraversalIgnoreInsertionOrderAndDeduplicateAncestors() {
        var a = skill("test.a"); var b = skill("test.b"); var c = skill("test.c");
        var d = skill("test.d"); var separate = skill("test.e");
        var members = new ArrayList<>(List.of(separate, d, c, b, a));
        var edges = new ArrayList<>(List.of(edge(a, c), edge(a, b), edge(c, d), edge(b, d)));
        for (int seed = 0; seed < 20; seed++) {
            Collections.shuffle(members, new Random(seed));
            Collections.shuffle(edges, new Random(seed + 50));
            var graph = new SkillGraph(new GraphRef("test.diamond", 1), members, edges);
            assertEquals(List.of(a, b, c, d, separate), graph.topologicalOrder());
            assertEquals(List.of(a, b, c), graph.prerequisiteTraversal(d.ref()));
            assertEquals(List.of(b, c), graph.dependentsOf(a.ref()));
        }
    }

    @Test
    void rejectsCyclesIncludingDisconnectedComponentsWithActionableReferences() {
        var a = skill("test.a"); var b = skill("test.b"); var root = skill("test.root");
        var failure = assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.cycle", 1),
                List.of(root, b, a), List.of(edge(a, b), edge(b, a))));
        assertTrue(failure.getMessage().contains("cycle"));
        assertTrue(failure.getMessage().contains("test.a"));
        var c = skill("test.c");
        assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.long-cycle", 1),
                List.of(a, b, c), List.of(edge(a, b), edge(b, c), edge(c, a))));
    }

    @Test
    void rejectsMissingEndpointsAndWrongDefinitionVersionsRatherThanDroppingEdges() {
        var a = skill("test.a"); var b = skill("test.b");
        assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.missing", 1),
                List.of(a), List.of(edge(a, b))));
        var wrongVersion = new SkillPrerequisite(new SkillRef("test.a", 2), b.ref());
        assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.version", 1),
                List.of(a, b), List.of(wrongVersion)));
        var graph = new SkillGraph(new GraphRef("test.query", 1), List.of(a), List.of());
        assertThrows(IllegalArgumentException.class, () -> graph.prerequisitesOf(new SkillRef("test.a", 2)));
        assertThrows(IllegalArgumentException.class, () -> graph.dependentsOf(b.ref()));
        assertThrows(IllegalArgumentException.class, () -> graph.prerequisiteTraversal(b.ref()));
    }

    @Test
    void rejectsDuplicateEdgesAndDuplicateOrMixedVersionMembers() {
        var a = skill("test.a"); var b = skill("test.b");
        assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.duplicate", 1),
                List.of(a, b), List.of(edge(a, b), edge(a, b))));
        assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.duplicate", 1), List.of(a, a), List.of()));
        var a2 = new SkillDefinition(new SkillRef("test.a", 2), a.domainId(), a.name(), a.description(), a.observableBehaviors(), a.lifecycle());
        assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.mixed", 1), List.of(a, a2), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SkillGraph(new GraphRef("test.empty", 1), List.of(), List.of()));
    }

    @Test
    void rejectsSelfReferencesAcrossDefinitionVersionsToo() {
        assertThrows(IllegalArgumentException.class, () -> new SkillPrerequisite(new SkillRef("test.a", 1), new SkillRef("test.a", 1)));
        assertThrows(IllegalArgumentException.class, () -> new SkillPrerequisite(new SkillRef("test.a", 1), new SkillRef("test.a", 2)));
    }

    @Test
    void immutableValuesRetainRetiredAndCrossDomainDefinitionsWithoutInferringProficiency() {
        var behaviors = new ArrayList<>(List.of("Produces an observable result\nwith a regression check"));
        var retired = new SkillDefinition(new SkillRef("reasoning.trace", 2), "reasoning", "Trace state", "Trace a sequence",
                behaviors, SkillLifecycle.RETIRED);
        var b = skill("test.b");
        var members = new ArrayList<>(List.of(b, retired));
        var edges = new ArrayList<>(List.of(edge(retired, b)));
        var graph = new SkillGraph(new GraphRef("cross-domain", 7), members, edges);
        behaviors.clear(); members.clear(); edges.clear();
        assertEquals(List.of(retired, b), graph.topologicalOrder());
        assertEquals(1, retired.observableBehaviors().size());
        assertThrows(UnsupportedOperationException.class, () -> retired.observableBehaviors().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.prerequisites().clear());
    }

    @Test
    void validatesIdentifiersVersionsAndObservableBehaviors() {
        for (String id : new String[]{"", " ", "test..bad", "Java.Threads", "test.a ", "a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> new SkillRef(id, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new SkillRef("valid", 0));
        assertThrows(IllegalArgumentException.class, () -> new GraphRef("valid", -1));
        assertThrows(IllegalArgumentException.class, () -> new GraphRef("bad id", 1));
        var ref = new SkillRef("test.valid", 1);
        assertThrows(IllegalArgumentException.class, () -> new SkillDefinition(ref, "domain", "Name", "Desc", List.of(), SkillLifecycle.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new SkillDefinition(ref, "domain", "Name", "Desc", List.of(" "), SkillLifecycle.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new SkillDefinition(ref, "domain", "Name", "Desc", List.of("x", "x"), SkillLifecycle.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new SkillDefinition(ref, "domain", "", "Desc", List.of("x"), SkillLifecycle.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new SkillDefinition(ref, "bad domain", "Name", "Desc", List.of("x"), SkillLifecycle.ACTIVE));
        assertThrows(NullPointerException.class, () -> new SkillDefinition(ref, "domain", "Name", "Desc", List.of("x"), null));
    }
}
