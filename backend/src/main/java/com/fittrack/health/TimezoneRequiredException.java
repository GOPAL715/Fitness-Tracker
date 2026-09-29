package com.fittrack.health;

/**
 * A health record batch arrived without a usable IANA timezone (D3).
 *
 * <h2>Why this is refused rather than defaulted</h2>
 * An instant has no calendar date until somebody supplies a calendar. Filing a record under a
 * server-local day, or under UTC, or under any invented zone, would silently mis-date real health
 * data - and it would do so for precisely the users who never told us where they are, which is
 * every user who registered before this phase. There is no safe default here: {@code UTC} is a real
 * zone many people genuinely live in, so defaulting to it is indistinguishable from a real answer
 * and wrong for everyone else.
 *
 * <p>So ingestion stops, writes nothing, and tells the caller what to do. The response carries a
 * stable {@code timezone_required} code so a bridge can react without parsing the message.
 */
public class TimezoneRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TimezoneRequiredException(String message) {
        super(message);
    }
}