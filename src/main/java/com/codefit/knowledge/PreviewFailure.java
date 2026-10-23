package com.codefit.knowledge;

/** No parser messages, paths, supplied values, or exception causes cross this boundary. */
public final class PreviewFailure extends IllegalArgumentException {
    public enum Code {
        IO, TOO_LARGE, INVALID_JSON, LIMIT, DUPLICATE_FIELD, FIELDS, TYPE, VERSION,
        IDENTIFIER, FINGERPRINT, FLAGS, COUNT, DUPLICATE_ID, STAGES,
        MISSING_REFERENCE, CYCLE, PIN_MISMATCH, RESOURCE_PIN, CLI
    }
    private final Code code;

    PreviewFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
