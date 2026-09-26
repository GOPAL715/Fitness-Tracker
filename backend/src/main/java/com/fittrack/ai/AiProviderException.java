package com.fittrack.ai;

public class AiProviderException extends RuntimeException {
    private final String category;
    public AiProviderException(String message, String category) { super(message); this.category = category; }
    public String category() { return category; }
}
