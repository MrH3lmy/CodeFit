# Dual-instance real P2P smoke test

This harness runs the normal `com.codefit.CodeFitLauncher` entry point twice, which immediately delegates
to `CodeFitApplication`. It does not use test fixtures, fake controllers, or mocked networking. Build
output and both development installations live under the repository's ignored `.build/dev-peers/`
directory.

## Persistence and isolation

CodeFit's durable application and P2P state is stored as follows:

* `codefit.db` is relative to the process working directory. It contains learning data as well as the
  encrypted local identity private key, transport private key, contacts, invitations/bindings, peer
  summaries, publication outbox, and other sync state. The harness uses separate `a/work` and `b/work`
  directories, producing separate database files.
* Java `Preferences` contains application preferences (including progress and navigation state). Each
  JVM receives a distinct `user.home` and explicit `java.util.prefs.userRoot`.
* Each JVM receives its own `java.io.tmpdir` for SQLite's extracted native library and temporary Java
  exercise work. Temporary exercise output is not durable identity, but isolating it avoids incidental
  cross-process sharing.
* Workbook imports, exports, and identity backups go only to paths explicitly selected in a file chooser;
  the application has no additional implicit durable cache. Do not select the same external output file
  in both windows if independent copies are desired.

The resulting persistent profiles are:

* **A:** `.build/dev-peers/a/` (database: `a/work/codefit.db`)
* **B:** `.build/dev-peers/b/` (database: `b/work/codefit.db`)

They persist between runs. Generated runtime classpath data, logs, databases, preferences, and key
material beneath `.build/dev-peers/` are ignored by Git.

## Launch and reset

From the repository root, run:

```bash
./scripts/dev/run-dual-codefit.sh
```

The launcher requires Java 21, Maven, and a graphical session. It builds the application and resolves
the runtime classpath once, then starts two real JavaFX JVMs with absolute application/classpath paths.
Application output goes to each profile's `logs/codefit.log`. Closing either application or pressing
Ctrl+C terminates both children so the launcher does not leave an orphan process.

Networking uses the application's normal **Enable networking** action. Each instance asks the OS for an
available listening port, so both can run on one host without configured or hard-coded port numbers.

For fresh identities and data, run:

```bash
./scripts/dev/run-dual-codefit.sh --reset
```

Reset deletes only the exact harness-owned `a/` and `b/` directories after canonical-path and symlink
checks; it cannot target a user profile, repository data, or a normal `codefit.db`. To check isolation and
reset guards without launching JavaFX, run `./scripts/dev/test-dual-codefit-isolation.sh`.

## Initial setup

1. Launch the harness and identify windows A and B by the launcher's profile/log paths (arrange them on
   opposite sides of the screen if useful).
2. Create a different local identity in A and in B. Vault passphrases may be different and are never
   printed by the launcher.
3. In each window, enable networking and note its independently OS-assigned port.
4. Open **Add a peer**. Create an invitation in A and paste it into B; create an invitation in B and paste it into A.
5. Verify the displayed fingerprints out of band, then pair the contacts on both sides.
6. Connect one installation to the other using the invitation/contact address and verify both show the
   real connection.

## Current #199 acceptance flow

1. Perform real study activity independently in both A and B.
2. In each installation, use **Share progress** on the other peer's card.
3. Sync over the established P2P connection.
4. In each installation, use **Compare today** on the other peer's card.
5. Verify that each profile retains its own local study data and displays the other installation's shared
   summary. Restart the launcher without `--reset` and confirm identities, contacts, and summaries remain.

Never paste secrets or private-key material into logs or issue reports. Invitations are intended for
out-of-band exchange, but should still be handled only by the developers participating in the smoke test.
