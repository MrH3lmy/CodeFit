package com.codefit.peer.identity;

/** Thrown when a backup file declares a format version this build does not know how to read. */
public class UnsupportedBackupVersionException extends RuntimeException {
    public UnsupportedBackupVersionException(String message) {
        super(message);
    }
}
