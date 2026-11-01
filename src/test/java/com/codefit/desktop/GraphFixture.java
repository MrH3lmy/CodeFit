package com.codefit.desktop;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.repository.*;

import java.nio.file.Path;
import java.util.List;

/** Host-only synthetic catalog provisioner; never included in the packaged application. */
public final class GraphFixture {
    public static void main(String[] args) throws Exception {
        try (var p = ExperimentalProfile.openLearning(Path.of(args[0]));
                var c = p.connection(false)) {
            c.setAutoCommit(false);
            var catalog = new SkillCatalogRepository();
            var pilot = JavaDebuggingSkillSeed.graph();
            catalog.publishGraph(
                    c,
                    new SkillGraph(
                            new GraphRef(pilot.ref().graphId(), 2),
                            pilot.definitions(),
                            pilot.prerequisites()));
            var s =
                    new SkillDefinition(
                            new SkillRef("synthetic.retired", 1),
                            "software-engineering",
                            "Retired test fixture",
                            "Synthetic test metadata only",
                            List.of("Checks a test invariant"),
                            SkillLifecycle.RETIRED);
            catalog.publishDefinition(c, s);
            catalog.publishGraph(
                    c,
                    new SkillGraph(
                            new GraphRef("synthetic.incompatible", 1), List.of(s), List.of()));
            var retired = new GraphRef("synthetic.retired", 1);
            catalog.publishGraph(
                    c, new SkillGraph(retired, pilot.definitions(), pilot.prerequisites()));
            if (new GraphSelectionRepository().lifecycle(c, retired).revision() == 0)
                new GraphSelectionRepository().lifecycle(c, retired, 0, "RETIRED", null);
            c.commit();
            var app = new PracticeApplicationService(p, java.time.Clock.systemUTC());
            var candidate = app.list().getFirst();
            app.open(
                    "e2e00000-0000-4000-8000-000000000001",
                    new com.codefit.evolution.quest.CatalogRef(
                            candidate.questId(), candidate.questVersion()),
                    new com.codefit.evolution.quest.CatalogRef(
                            candidate.challengeId(), candidate.challengeVersion()));
        }
    }
}
