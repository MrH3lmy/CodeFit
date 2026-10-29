# Verification — Skill Analytics (#283)

Final verification is in progress. The PR description will record the final commit SHA and GitHub Actions links; do not treat earlier focused runs as final CI approval.

## Commands

Java 21 with Xvfb:

```sh
mvn -B test
```

Desktop (Java 21 available as `JAVA_HOME`):

```sh
npm run prepare:java
npm run build
npm test
npm run package
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" xvfb-run -a npm run test:e2e
npm audit
```

Local packaged runs additionally use `scripts/offline-linux.sh`, a Linux network namespace with only loopback enabled. Chromium sandbox and context isolation stay enabled. Screenshots are captured with `CODEFIT_CAPTURE_ANALYTICS_SCREENSHOTS=1`; no private sources or evaluator assets appear in them.

## Scope and files

- Java: `PracticeAnalytics` query-only adapter; `PracticeApplicationMain` and `PracticeProtocol` finite command; three package-visible validated helpers in `LearnerSkillProjection`.
- Electron: `main`, `preload`, `practice-owner`, `practice-session` allowlist, typed dispatch, identity/page/digest correlation.
- React: `analytics-contract`, `skill-analytics`, `contract`, `practice-contract`, navigation in `main.tsx`, additive analytics CSS.
- Tests: `PracticeAnalyticsTest`, host-only `AnalyticsFixture`, Node `analytics-fixture`/`practice-analytics.test`, packaged `analytics.spec`; existing desktop bridge allowlist expectation gains precisely one method.
- Documentation and 20 packaged screenshots under this directory.

No dependency/lockfile, schema migration, policy, evaluation, curriculum, P2P payload or JavaFX controller changes.

## Security/dependency result

`npm audit`: one existing **low** esbuild development-server advisory, GHSA-g7r4-m6w7-qqqr; zero moderate/high/critical findings. No dependencies added or changed. This Linux packaged application serves bundled assets, not an esbuild Windows development server. The existing advisory remains a dependency-maintenance follow-up, not a reason to change the learning architecture or bundle a new chart library.

Native Windows/macOS practice ownership remains disabled. Manual assistive-technology testing on those platforms is not claimed. Automated keyboard/accessibility semantics and actual Linux visual inspection cover both supported screenshot widths/themes.
