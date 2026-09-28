package com.fittrack.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fittrack.coach.CoachDtos.CoachInsightPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 9 strict validation of the provider's structured Coach response.
 *
 * <p>Syntactically valid JSON is not trusted. A model can return
 * {@code {"summary": 123}} or a 400-element array, and neither is a usable Coach answer. Every
 * field is therefore range-checked here, and anything that fails raises a provider error rather
 * than reaching the browser as an apparently valid response.
 *
 * <p>Lengths and array sizes are bounded. Truncation happens only on array <em>count</em> and never
 * silently on a single over-long string: a summary that exceeds the limit is a provider fault, not
 * something to quietly repair, because a half-sentence presented as a summary is worse than an
 * error the user can retry.
 */
@Component
public class CoachInsightParser {

    static final int MAX_SUMMARY = 2_000;
    static final int MAX_ITEM = 500;
    static final int MAX_ARRAY = 12;

    private final ObjectMapper mapper;

    public CoachInsightParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses and validates the assistant's content.
     *
     * @param content raw provider message content
     * @return a validated payload that is safe to return to the client
     * @throws InvalidCoachResponseException when the structure is unusable
     */
    public CoachInsightPayload parse(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidCoachResponseException("provider returned an empty response");
        }
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (Exception e) {
            throw new InvalidCoachResponseException("provider response was not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidCoachResponseException("provider response was not a JSON object");
        }

        String summary = requiredText(root, "summary", MAX_SUMMARY);
        return CoachInsightPayload.of(
                summary,
                textArray(root, "observations"),
                textArray(root, "recommendations"),
                textArray(root, "next_actions"),
                textArray(root, "warnings"));
    }

    private String requiredText(JsonNode root, String field, int max) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new InvalidCoachResponseException("provider response omitted " + field);
        }
        if (!node.isTextual()) {
            throw new InvalidCoachResponseException(field + " must be a string");
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            throw new InvalidCoachResponseException(field + " must not be blank");
        }
        if (value.length() > max) {
            throw new InvalidCoachResponseException(field + " exceeded the maximum length");
        }
        return value;
    }

    /**
     * Reads an array of strings. An absent or null array is an empty list, which is legitimate: a
     * week with no warnings is a normal answer. A present-but-wrong-typed array is a fault.
     */
    private List<String> textArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new InvalidCoachResponseException(field + " must be an array");
        }
        if (node.size() > MAX_ARRAY) {
            throw new InvalidCoachResponseException(field + " contained too many items");
        }
        List<String> items = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new InvalidCoachResponseException(field + " must contain only strings");
            }
            String value = item.asText().trim();
            if (value.isEmpty()) continue;
            if (value.length() > MAX_ITEM) {
                throw new InvalidCoachResponseException(field + " contained an over-long item");
            }
            items.add(value);
        }
        return items;
    }

    /** Provider output that cannot be validated. Never carries the provider payload. */
    public static class InvalidCoachResponseException extends RuntimeException {
        public InvalidCoachResponseException(String message) {
            super(message);
        }
    }
}
