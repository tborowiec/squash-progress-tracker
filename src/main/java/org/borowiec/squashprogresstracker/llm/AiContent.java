package org.borowiec.squashprogresstracker.llm;

/**
 * Envelope for any AI-derived response. Slices must wrap AI output in this record
 * so the advice label travels in every response and cannot be omitted.
 */
public record AiContent<T>(T content, boolean aiGenerated, String disclaimer) {

    public static <T> AiContent<T> of(T content) {
        return new AiContent<>(content, true, AiDisclaimer.TEXT);
    }
}
