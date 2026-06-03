package org.borowiec.squashprogresstracker.llm.client;

public class LlmException extends RuntimeException {

    private final Integer providerStatus;

    public LlmException(String message, Throwable cause, Integer providerStatus) {
        super(message, cause);
        this.providerStatus = providerStatus;
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public LlmException(String message) {
        this(message, null, null);
    }

    public Integer providerStatus() {
        return providerStatus;
    }
}
