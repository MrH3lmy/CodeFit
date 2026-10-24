# Phase 1 verification

## Environment and method

2026-10-18: Debian 13 x86_64, Temurin 21.0.12.1+1, JavaFX 21.0.5, Maven 3.9.11,
Xvfb 1920×1200 (1×) and 2560×1800 (1.5×). The capture launcher calls the **actual
`CodeFitApplication.start`**, with a fresh temporary SQLite file and isolated Java preferences.
Today and Evolution use their production FXML/controllers/services. Nothing is written to a learner
profile, no learner pilot is executed, and no challenge prompt or solution is exposed for capture.

The test-only component gallery is labelled **preview only**. Its sample operation progress, badges,
and unavailable skill node demonstrate shared styles and do not create evidence or rewards.

## Screenshots

Logical window sizes are 1280×900 and 760×900. Images are direct JavaFX scene snapshots, not mockups.
The narrow layout at 760×560 also has a real scene regression for scrolling/navigation hit targets.

| Surface | Desktop dark | Desktop light | 760px dark | 760px light |
| --- | --- | --- | --- | --- |
| Actual Today / persistent shell | [image](images/today-dark-1280.png) | [image](images/today-light-1280.png) | [image](images/today-dark-760.png) | [image](images/today-light-760.png) |
| Shared component specimens | [image](images/components-dark-1280.png) | [image](images/components-light-1280.png) | [image](images/components-dark-760.png) | [image](images/components-light-760.png) |

- Actual Evolution at 760px: [dark](images/evolution-dark-760.png), [light](images/evolution-light-760.png).
- Empty/loading/error/success states: [dark](images/states-dark.png), [light](images/states-light.png).
- [125% font-size stress at 760px](images/components-light-760-text125.png). The launcher applies
  inline font sizes after CSS; this is a layout stress check, not a native OS accessibility setting.
- Actual 1.5× JavaFX output scaling (`Stage.outputScaleX == 1.5`), 1920×1350 pixel captures:
  [dark](images/today-dark-1280-scale150.png), [light](images/today-light-1280-scale150.png).

Visual inspection checked hierarchy, palette, control ink, wrapping, focus/selection distinction,
scrolling and preservation of actual content. It found and fixed malformed space-separated FXML
style lists in the touched shell/Today surface, narrow Today action overflow, clipped Evolution
header/Refresh, and the enlarged-text sidebar section label. No screenshot-based claim is made
about hidden content, grading correctness or human learning outcomes.

## Automated checks

```sh
# Java 21; actual JavaFX display (also used by repository GitHub Actions)
xvfb-run -a mvn -B -Dtest=SystemShellTest,SystemPaletteTest,RouteTest,StylesheetResourcesTest,NavigationServiceThemeTest,FxmlLoadingTest,EvolutionNavigationTest,EvolutionControllerTest,ProblemSolvingWorkspaceJavaRunnerBusyStateTest test
xvfb-run -a mvn -B test
git diff --check
```

New tests verify the eleven-token contract and 4.5:1 text/on-fill contrast; computed control contrast
and focus; persistent shell identity; Settings/global-action selection; actual Settings theme switch;
Review focus mode; 760px/short-window navigation; detach/reattach sizing; real Today action bounds;
and static, explicit shared states. Minimum tested semantic foreground contrast across all three
surfaces is **6.15:1 dark / 5.14:1 light**. This is not a full WCAG or screen-reader audit.

Existing focused tests cover all FXML/routes, legacy theme preference migration, actual Evolution
navigation, cold-start saved-submission recovery and unrelated drafts, and runner busy-state/lifecycle
cleanup. `IsolatedDatabaseExtension` is unchanged. Commit-specific focused/full test counts and
GitHub CI status are recorded in the PR description so updating test status does not change the
implementation SHA.

### Reproduce visual captures

Run these in a disposable workspace with Java 21 and a working JavaFX display. The launcher creates
its own temporary SQLite file; use a temporary preferences directory as shown. It is not a product route.

```sh
mvn -B test-compile dependency:build-classpath -Dmdep.outputFile=target/visual-classpath.txt
visual_prefs=$(mktemp -d)
visual_cp="target/test-classes:target/classes:$(cat target/visual-classpath.txt)"
xvfb-run -a -s '-screen 0 1920x1200x24' java \
  -Djava.util.prefs.userRoot="$visual_prefs" -cp "$visual_cp" \
  com.codefit.ui.SystemVisualCapture target/system-visual
# Repeat with a separate preferences directory and larger X display for 1.5x output:
xvfb-run -a -s '-screen 0 2560x1800x24' java \
  -Dglass.gtk.uiScale=1.5 -Djava.util.prefs.userRoot="$visual_prefs" -cp "$visual_cp" \
  com.codefit.ui.SystemVisualCapture target/system-visual-150
```

## Adversarial review and limits

- Shell lifecycle callbacks and navigation generation remain unchanged; no new exposure path,
  challenge editor enablement, saved submission, runner or repository access was introduced.
- Settings now has its own navigation identity; global actions clear the old selection. Focus and
  active route are separate states. Compact mode never replaces route names with icons alone.
- New components accept display text only and make no mastery/reward calculation. Existing
  Evolution engagement/proficiency distinctions stay visible. Static local-workspace text is not
  a claim that opted-in P2P sharing is disabled.
- No schema, migrations, P2P serializers, database URL behavior, XP policy or evaluator changed.
  Token-dependent legacy selected states (Library/Progress/Peers) use explicit on-accent ink.
- Actual Windows/macOS display, OS text scaling and screen-reader announcements remain unverified;
  carry this matrix into **[#238](https://github.com/MrH3lmy/CodeFit/issues/238)**. Validate Tab/Shift+Tab,
  activation, focus visibility, long labels, short-window scroll, theme switching, dialogs, and
  cold-start Resume/Cancel on each native platform. Runner platform validation remains #228.
- Existing Evolution tabs use JavaFX's overflow menu at narrow widths; all tabs remain available.
  This phase does not redesign the Command Center or mission editor.
- Linux Xvfb emitted a GTK resize warning while hiding the capture window at shutdown. Capture
  processes exited successfully; inspected images and scene/controller checks were unaffected.
  There were no final CSS conversion warnings. This is not evidence of native-platform certification.

Stop after Phase 1 review. Begin #250 only after review/merge and explicit continuation; preserve the
same service ownership and truthful evidence vocabulary described in [phase-1.md](phase-1.md).
