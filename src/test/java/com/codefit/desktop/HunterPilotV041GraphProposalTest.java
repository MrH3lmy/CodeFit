package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.knowledge.KnowledgePreview;
import com.codefit.repository.GraphSelectionRepository.Command;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class HunterPilotV041GraphProposalTest {
    private record Edge(String dependent, String prerequisite, String kind, String disposition) {}

    private static final Path PROPOSAL =
            Path.of("docs/knowledge-compiler/hunter-pilot-v0.41/graph-proposal.json");
    @TempDir Path directory;

    private static Object value(JsonParser parser) throws Exception {
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                var object = new LinkedHashMap<String, Object>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = parser.currentName();
                    parser.nextToken();
                    assertFalse(object.containsKey(field), "duplicate proposal field: " + field);
                    object.put(field, value(parser));
                }
                yield object;
            }
            case START_ARRAY -> {
                var array = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) array.add(value(parser));
                yield array;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("Invalid proposal JSON");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> proposal() throws Exception {
        try (var parser = new JsonFactory().createParser(Files.readAllBytes(PROPOSAL))) {
            assertNotNull(parser.nextToken());
            var result = object(value(parser));
            assertNull(parser.nextToken());
            return result;
        }
    }

    private static SkillDefinition fixture(String id) {
        return new SkillDefinition(
                new SkillRef(id, 1),
                "test-only",
                "Disposable topology fixture",
                "Not a curriculum definition",
                List.of("Test-only placeholder behavior"),
                SkillLifecycle.ACTIVE);
    }

    private static void requireUniqueTargets(Map<String, SkillRef> targets) {
        if (new HashSet<>(targets.values()).size() != targets.size())
            throw new IllegalArgumentException("MAPPING_COLLISION");
    }

    @Test
    void proposalMatchesPinnedSourceAndKeepsAllMappingsExplicitlyBlocked() throws Exception {
        var source = KnowledgePreview.loadBundled();
        var proposal = proposal();
        assertEquals(1L, proposal.get("schemaVersion"));
        assertEquals(KnowledgePreview.REVISION, proposal.get("sourceVersion"));
        assertEquals(KnowledgePreview.SHA256, proposal.get("sourceSha256"));
        assertEquals("AUTHOR_DRAFT_PENDING_EXTERNAL_REVIEW", proposal.get("status"));
        assertEquals(0L, proposal.get("independentApprovals"));
        assertEquals(false, proposal.get("productionPublication"));
        assertEquals(false, proposal.get("practiceEligible"));
        assertNull(proposal.get("candidateGraphRef"));

        var existing = object(proposal.get("existingGraphRef"));
        assertEquals(JavaDebuggingSkillSeed.GRAPH.graphId(), existing.get("graphId"));
        assertEquals((long) JavaDebuggingSkillSeed.GRAPH.graphVersion(), existing.get("graphVersion"));

        var proposedSkills = array(proposal.get("skills"));
        assertEquals(source.skills().size(), proposedSkills.size());
        var byCompilerId = new HashMap<String, Map<String, Object>>();
        for (Object value : proposedSkills) {
            var skill = object(value);
            String compilerId = (String) skill.get("compilerId");
            assertNull(byCompilerId.put(compilerId, skill), "duplicate compiler ID");
            assertNull(skill.get("targetSkillRef"));
            assertEquals("MISSING_REVIEW_REQUIRED", skill.get("mappingDisposition"));
        }
        for (var sourceSkill : source.skills()) {
            var skill = byCompilerId.get(sourceSkill.id());
            assertNotNull(skill);
            assertEquals(sourceSkill.sourceItem(), skill.get("sourceItem"));
            assertEquals(sourceSkill.sourceSectionSha256(), skill.get("sourceSectionSha256"));
            assertEquals(sourceSkill.prerequisites(), array(skill.get("prerequisiteIds")));
        }
        var existingIds = JavaDebuggingSkillSeed.graph().definitions().stream()
                .map(skill -> skill.ref().skillId())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(byCompilerId.keySet().stream().noneMatch(existingIds::contains));
        var expectedEdges = new HashSet<Edge>();
        for (var skill : source.skills()) {
            for (String prerequisite : skill.prerequisites()) {
                boolean internal = byCompilerId.containsKey(prerequisite);
                expectedEdges.add(new Edge(
                        skill.id(),
                        prerequisite,
                        internal ? "internal" : "external",
                        internal ? "PRESERVED" : "MISSING_REVIEW_REQUIRED"));
            }
        }
        var actualEdges = array(proposal.get("prerequisiteEdges")).stream()
                .map(HunterPilotV041GraphProposalTest::object)
                .map(edge -> new Edge(
                        (String) edge.get("dependentCompilerId"),
                        (String) edge.get("prerequisiteId"),
                        (String) edge.get("kind"),
                        (String) edge.get("disposition")))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedEdges, actualEdges);
        assertTrue(expectedEdges.stream()
                .filter(edge -> edge.kind().equals("external"))
                .noneMatch(edge -> existingIds.contains(edge.prerequisite())));
        assertEquals(
                Set.of(
                        "java.control.flow",
                        "java.exceptions.checked",
                        "java.collections.iteration",
                        "java.numeric.integer.overflow",
                        "java.api.contracts",
                        "java.api.preconditions.v1"),
                array(proposal.get("prerequisiteEdges")).stream()
                        .map(HunterPilotV041GraphProposalTest::object)
                        .filter(edge -> edge.get("kind").equals("external"))
                        .map(edge -> (String) edge.get("prerequisiteId"))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                7L,
                array(proposal.get("prerequisiteEdges")).stream()
                        .filter(value -> object(value).get("kind").equals("external"))
                        .count());
        assertEquals(
                1L,
                array(proposal.get("prerequisiteEdges")).stream()
                        .filter(value -> object(value).get("disposition").equals("PRESERVED"))
                        .count());
    }

    @Test
    void disposableFourNodeGraphIsAcyclicAndPreservesInternalEdge() throws Exception {
        var skills = KnowledgePreview.loadBundled().skills();
        var definitions = skills.stream().map(skill -> fixture(skill.id())).toList();
        var local = definitions.stream()
                .filter(skill -> skill.ref().skillId().equals("java.general.local.scope.v1"))
                .findFirst().orElseThrow();
        var iteration = definitions.stream()
                .filter(skill -> skill.ref().skillId().equals("java.general.enhanced.iteration.v1"))
                .findFirst().orElseThrow();
        var edge = new SkillPrerequisite(local.ref(), iteration.ref());
        var graph = new SkillGraph(new GraphRef("test.hunter-pilot-v0-41", 1), definitions, List.of(edge));

        assertEquals(4, graph.topologicalOrder().size());
        assertEquals(List.of(local), graph.prerequisitesOf(iteration.ref()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillGraph(
                        graph.ref(),
                        definitions,
                        List.of(edge, new SkillPrerequisite(iteration.ref(), local.ref()))));
    }

    @Test
    void mappingCollisionFixtureRejectsTwoCompilerIdsSharingOneExactSkillRef() {
        var existing = JavaDebuggingSkillSeed.graph().definitions().getFirst().ref();
        var collided = Map.of(
                "java.general.local.scope.v1", existing,
                "java.general.enhanced.iteration.v1", existing);

        assertThrows(
                IllegalArgumentException.class,
                () -> requireUniqueTargets(collided));
    }

    @Test
    void staleOrUnregisteredDraftSelectionIsRejectedInDisposableProfile() throws Exception {
        try (var profile = ExperimentalProfile.openLearning(directory.resolve("profile"))) {
            var service = new PracticeGraphService(profile, Clock.systemUTC());
            var current = service.read(null);
            assertEquals(JavaDebuggingSkillSeed.GRAPH, current.selection().graph());
            assertEquals(
                    List.of(JavaDebuggingSkillSeed.GRAPH),
                    current.graphs().stream().map(PracticeGraphService.Summary::ref).toList());
            assertNull(
                    getClass().getClassLoader().getResource(
                            "knowledge/hunter-pilot-v0.41/graph-proposal.json"));

            var proposed = new GraphRef("test.unregistered-hunter-proposal", 1);
            var missing = new Command(
                    UUID.randomUUID().toString(),
                    profile.id(),
                    UUID.randomUUID().toString(),
                    PracticeGraphService.TRACK,
                    proposed,
                    current.selection().revision());
            assertEquals("GRAPH_MISSING", service.choose(missing).reason());
            var stale = new Command(
                    UUID.randomUUID().toString(),
                    profile.id(),
                    UUID.randomUUID().toString(),
                    PracticeGraphService.TRACK,
                    JavaDebuggingSkillSeed.GRAPH,
                    current.selection().revision() + 1);
            assertEquals("SELECTION_CONFLICT", service.choose(stale).reason());

            var after = service.read(null);
            assertEquals(current.selection(), after.selection());
            assertEquals(List.of(JavaDebuggingSkillSeed.GRAPH),
                    after.graphs().stream().map(PracticeGraphService.Summary::ref).toList());
            assertNotNull(after.detail());
            assertTrue(after.detail().definitions().stream()
                    .noneMatch(skill -> skill.ref().skillId().startsWith("java.general.")));
        }
    }
}
