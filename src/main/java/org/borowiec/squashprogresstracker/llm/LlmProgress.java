package org.borowiec.squashprogresstracker.llm;

/**
 * Vocabulary for the "continuous progress feedback" NFR (prd.md:102).
 *
 * F-02 ships this vocabulary only. It does NOT emit these states or build
 * streaming transport — that is S-02's responsibility. S-02 realizes this
 * contract over SSE; F-02 itself is synchronous.
 */
public enum LlmProgress {
    SUBMITTED("Submitted"),
    GENERATING("Generating"),
    COMPLETED("Completed"),
    FAILED("Failed");

    private final String label;

    LlmProgress(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
