package com.codefit.knowledge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static com.codefit.knowledge.PreviewFailure.Code.*;

/** Fixed-purpose offline build/export; no host registration or production importer. */
public final class KnowledgePreviewCli {
    private KnowledgePreviewCli() { }

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !(args[0].equals("build") || args[0].equals("export")))
                throw KnowledgePreview.fail(CLI);
            KnowledgePreview.loadBundled();
            PublicPreview preview;
            try (InputStream input = Files.newInputStream(Path.of(args[1]))) {
                preview = KnowledgePreview.verify(KnowledgePreview.read(input));
            }
            byte[] artifact = export(preview);
            if (args[0].equals("export")) publish(artifact);
            System.out.println(KnowledgePreview.sha256(artifact));
        } catch (PreviewFailure e) {
            System.err.println(e.code().name());
            System.exit(1);
        } catch (IOException | InvalidPathException | SecurityException e) {
            System.err.println(IO.name());
            System.exit(1);
        }
    }

    private static void publish(byte[] artifact) throws IOException {
        publish(artifact, () -> {});
    }

    // Package-private observer makes the post-rename ownership race reproducible in tests.
    static void publish(byte[] artifact, Runnable afterPublication) throws IOException {
        Path target = Path.of("target"), directory = target.resolve("knowledge-preview");
        if (Files.isSymbolicLink(target)) throw KnowledgePreview.fail(IO);
        Files.createDirectories(target);
        byte[] checksum = (KnowledgePreview.sha256(artifact) + "  public-preview.json\n")
                .getBytes(StandardCharsets.US_ASCII);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            requireIdentical(directory, artifact, checksum);
            return;
        }
        Path staging = target.resolve(".knowledge-preview-staging");
        Files.createDirectory(staging);
        boolean published = false;
        try {
            write(staging.resolve("public-preview.json"), artifact);
            write(staging.resolve("public-preview.json.sha256"), checksum);
            // Publish both files together; unsupported atomic moves fail closed.
            try {
                Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
                published = true;
                afterPublication.run();
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
                requireIdentical(directory, artifact, checksum);
            }
        } finally {
            // A successful rename relinquishes this path; another exporter may now own it.
            if (!published) {
                Files.deleteIfExists(staging.resolve("public-preview.json"));
                Files.deleteIfExists(staging.resolve("public-preview.json.sha256"));
                Files.deleteIfExists(staging);
            }
        }
    }

    private static void requireIdentical(Path directory, byte[] artifact, byte[] checksum) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw KnowledgePreview.fail(OUTPUT_CONFLICT);
        requireIdenticalFile(directory.resolve("public-preview.json"), artifact);
        requireIdenticalFile(directory.resolve("public-preview.json.sha256"), checksum);
    }

    private static void requireIdenticalFile(Path path, byte[] expected) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expected.length)
            throw KnowledgePreview.fail(OUTPUT_CONFLICT);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!java.util.Arrays.equals(input.readNBytes(expected.length + 1), expected))
                throw KnowledgePreview.fail(OUTPUT_CONFLICT);
        }
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            output.write(bytes);
        }
    }

    /** Explicit public-metadata serializer shared by the CLI and finite desktop read. No I/O. */
    public static byte[] export(PublicPreview preview) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JsonGenerator json = new JsonFactory().createGenerator(bytes)) {
                json.writeStartObject();
                json.writeStringField("schema", preview.schema());
                json.writeStringField("exposure", preview.exposure());
                json.writeStringField("revision", preview.revision());
                json.writeStringField("revisionSha256", preview.revisionSha256());
                json.writeStringField("reviewStatus", preview.reviewStatus());
                json.writeStringField("verification", preview.verification());
                json.writeBooleanField("productionImportSupported", preview.productionImportSupported());
                json.writeBooleanField("graderAvailable", preview.graderAvailable());
                json.writeBooleanField("xpEnabled", preview.xpEnabled());
                json.writeBooleanField("rewardsEnabled", preview.rewardsEnabled());
                json.writeBooleanField("masteryEnabled", preview.masteryEnabled());
                json.writeNumberField("independentApprovals", preview.independentApprovals());
                json.writeStringField("caveat", preview.caveat());
                json.writeArrayFieldStart("skills");
                for (var skill : preview.skills()) {
                    json.writeStartObject();
                    json.writeStringField("id", skill.id());
                    json.writeStringField("title", skill.title());
                    json.writeStringField("sourceItem", skill.sourceItem());
                    json.writeStringField("sourceSectionSha256", skill.sourceSectionSha256());
                    json.writeArrayFieldStart("prerequisites");
                    for (String prerequisite : skill.prerequisites()) json.writeString(prerequisite);
                    json.writeEndArray();
                    json.writeArrayFieldStart("stages");
                    for (var stage : skill.stages()) {
                        json.writeStartObject();
                        json.writeStringField("stage", stage.stage());
                        json.writeStringField("id", stage.id());
                        json.writeEndObject();
                    }
                    json.writeEndArray();
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeArrayFieldStart("mappings");
                for (var mapping : preview.mappings()) {
                    json.writeStartObject();
                    json.writeStringField("id", mapping.id());
                    json.writeStringField("skillId", mapping.skill().skillId());
                    json.writeNumberField("definitionVersion", mapping.skill().definitionVersion());
                    json.writeStringField("graphId", mapping.graph().graphId());
                    json.writeNumberField("graphVersion", mapping.graph().graphVersion());
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeArrayFieldStart("blockers");
                for (var blocker : preview.blockers()) {
                    json.writeStartObject();
                    json.writeStringField("code", blocker.code());
                    json.writeStringField("id", blocker.id());
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeEndObject();
            }
            return bytes.toByteArray();
        } catch (IOException e) { throw KnowledgePreview.fail(IO); }
    }
}
