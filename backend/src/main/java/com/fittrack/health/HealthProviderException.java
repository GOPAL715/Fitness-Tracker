package com.fittrack.health;

/** Provider failure carrying a stable category; the message never reaches the client. */
public class HealthProviderException extends RuntimeException {

    private final String category;

    public HealthProviderException(String message, String category) {
        super(message);
        this.category = category;
    }

    public String category() { return category; }
}
