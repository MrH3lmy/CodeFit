import {
  closeSync,
  existsSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import {
  SkillsIntentSchema as IntentSchema,
  type SkillsIntent as Intent,
} from "../src/practice-skills-contract";
const MAX_REQUEST_BYTES = 4096;
import { strictJson } from "./strict-json";

/** One current user intent, owned by main, durably recorded BEFORE transport. No renderer storage. */
export class SkillsJournal {
  private readonly file: string;
  constructor(readonly directory: string) {
    this.file = join(directory, "pending-skills.json");
  }
  private directoryReady() {
    mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    if (realpathSync(this.directory) !== resolve(this.directory))
      throw new Error("JOURNAL_INVALID");
  }
  read(): Intent | null {
    this.directoryReady();
    if (!existsSync(this.file)) {
      // existsSync follows symlinks, so also reject a dangling file link.
      try {
        lstatSync(this.file);
        throw new Error("JOURNAL_INVALID");
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      }
      return null;
    }
    const stat = lstatSync(this.file);
    if (!stat.isFile() || stat.nlink !== 1 || stat.size > MAX_REQUEST_BYTES)
      throw new Error("JOURNAL_INVALID");
    return IntentSchema.parse(
      strictJson(
        new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(
          readFileSync(this.file),
        ),
      ),
    );
  }
  write(intent: Intent) {
    this.directoryReady();
    if (this.read()) throw new Error("RECOVERY_REQUIRED");
    const bytes = Buffer.from(JSON.stringify(IntentSchema.parse(intent)));
    if (bytes.length > MAX_REQUEST_BYTES) throw new Error("JOURNAL_INVALID");
    const temporary = join(this.directory, `pending-${randomUUID()}.tmp`);
    const fd = openSync(temporary, "wx", 0o600);
    try {
      writeFileSync(fd, bytes);
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
    // A crash before rename leaves an inert temporary file, never a dispatched command.
    renameSync(temporary, this.file);
    this.syncDirectory();
  }
  clear(commandId: string) {
    if (this.read()?.commandId !== commandId)
      throw new Error("RECOVERY_REQUIRED");
    unlinkSync(this.file);
    this.syncDirectory();
  }
  private syncDirectory() {
    if (process.platform === "win32") return;
    const fd = openSync(this.directory, "r");
    try {
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
  }
}
