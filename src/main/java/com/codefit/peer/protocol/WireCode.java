package com.codefit.peer.protocol;

/**
 * An enum constant with a stable one-byte wire code. Codes are part of the protocol: they are never
 * renumbered or reused, only appended. Decoding an unknown code is {@link RejectionReason#MALFORMED}
 * unless the enclosing field says otherwise (message types report {@code UNSUPPORTED_MESSAGE_TYPE}).
 */
interface WireCode {
    int code();

    static <E extends Enum<E> & WireCode> E fromCode(Class<E> type, int code) {
        for (E constant : type.getEnumConstants()) {
            if (constant.code() == code) {
                return constant;
            }
        }
        throw new ProtocolException(RejectionReason.MALFORMED, "Unknown " + type.getSimpleName() + " code " + code);
    }
}
