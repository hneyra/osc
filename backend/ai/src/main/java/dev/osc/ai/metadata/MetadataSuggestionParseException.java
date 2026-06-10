package dev.osc.ai.metadata;

public class MetadataSuggestionParseException extends RuntimeException {
    public MetadataSuggestionParseException(String message) {
        super(message);
    }

    public MetadataSuggestionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
