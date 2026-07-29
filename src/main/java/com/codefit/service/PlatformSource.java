package com.codefit.service;

/** How a row's platform was determined (#160): an explicit Platform column, inferred from the code/URL
 *  (see {@link PlatformInference}), or neither (falling back to a generic default). */
public enum PlatformSource {
    EXPLICIT,
    INFERRED,
    UNKNOWN
}
