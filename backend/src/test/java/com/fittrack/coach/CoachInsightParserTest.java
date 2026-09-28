package com.fittrack.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fittrack.coach.CoachDtos.CoachInsightPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9 / B-5: strict server-side validation of the provider's structured output.
 *
 * <p>These are the tests that stop a plausible-looking but wrong model answer from reaching the
 * browser. Valid JSON is not sufficient: type, length and array bounds are all enforced, because a
 * model will happily return {@code {"summary": 42}} or a 400-element list and the frontend would
 * otherwise render it as though it were a real Coach response.
 */
class CoachInsightParserTest {

    private final CoachInsightParser parser = new CoachInsightParser(new ObjectMapper());

    private static final String VALID = """
            {"summary":"A steady week.","observations":["Two sessions."],
             "recommendations":["Keep the rhythm."],"next_actions":["Book Friday."],"warnings":[]}""";

    // ----------------------------------------------------------------- valid

    @Test
    @DisplayName("a well-formed response parses into all five fields")
    void parsesAValidResponse() {
        CoachInsightPayload p = parser.parse(VALID);
        assertThat(p.summary()).isEqualTo("A steady week.");
        assertThat(p.observations()).containsExactly("Two sessions.");
        assertThat(p.recommendations()).containsExactly("Keep the rhythm.");
        assertThat(p.nextActions()).containsExactly("Book Friday.");
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    @DisplayName("absent optional arrays become empty lists rather than nulls")
    void missingArraysBecomeEmptyLists() {
        CoachInsightPayload p = parser.parse("{\"summary\":\"Minimal answer.\"}");
        assertThat(p.observations()).isEmpty();
        assertThat(p.recommendations()).isEmpty();
        assertThat(p.nextActions()).isEmpty();
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a null optional array is treated as empty, not as a fault")
    void nullArraysBecomeEmptyLists() {
        CoachInsightPayload p = parser.parse(
                "{\"summary\":\"S\",\"observations\":null,\"warnings\":null}");
        assertThat(p.observations()).isEmpty();
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    @DisplayName("whitespace-only array items are dropped instead of rendered as blanks")
    void blankArrayItemsAreDropped() {
        CoachInsightPayload p = parser.parse(
                "{\"summary\":\"S\",\"observations\":[\"real\",\"   \",\"\"]}");
        assertThat(p.observations()).containsExactly("real");
    }

    // -------------------------------------------------------------- required

    @Test
    @DisplayName("a missing summary is rejected")
    void rejectsMissingSummary() {
        assertThatThrownBy(() -> parser.parse("{\"observations\":[\"a\"]}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("summary");
    }

    @Test
    @DisplayName("a null summary is rejected")
    void rejectsNullSummary() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":null}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }

    @Test
    @DisplayName("a blank summary is rejected")
    void rejectsBlankSummary() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"   \"}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }

    // ------------------------------------------------------------------ type

    @Test
    @DisplayName("a non-string summary is rejected rather than coerced")
    void rejectsNonStringSummary() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":42}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("summary");
    }
    @Test
    @DisplayName("an array containing a non-string element is rejected")
    void rejectsNonStringArrayElement() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"S\",\"observations\":[{\"a\":1}]}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("only strings");
    }

    @Test
    @DisplayName("a nested array element is rejected")
    void rejectsNestedArrayElement() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"S\",\"warnings\":[[\"deep\"]]}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }

    // ---------------------------------------------------------------- bounds

    @Test
    @DisplayName("an oversized array is rejected rather than silently truncated")
    void rejectsOversizedArray() {
        String body = "{\"summary\":\"S\",\"observations\":[" + items(CoachInsightParser.MAX_ARRAY + 1) + "]}";
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("too many items");
    }

    @Test
    @DisplayName("an array exactly at the limit is accepted")
    void acceptsArrayAtTheLimit() {
        String body = "{\"summary\":\"S\",\"observations\":[" + items(CoachInsightParser.MAX_ARRAY) + "]}";
        assertThat(parser.parse(body).observations()).hasSize(CoachInsightParser.MAX_ARRAY);
    }

    @Test
    @DisplayName("an over-long summary is rejected, not truncated mid-sentence")
    void rejectsOverlongSummary() {
        String body = "{\"summary\":\"" + "x".repeat(CoachInsightParser.MAX_SUMMARY + 1) + "\"}";
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("maximum length");
    }

    @Test
    @DisplayName("a summary exactly at the limit is accepted")
    void acceptsSummaryAtTheLimit() {
        String body = "{\"summary\":\"" + "x".repeat(CoachInsightParser.MAX_SUMMARY) + "\"}";
        assertThat(parser.parse(body).summary()).hasSize(CoachInsightParser.MAX_SUMMARY);
    }

    @Test
    @DisplayName("an over-long array item is rejected")
    void rejectsOverlongArrayItem() {
        String body = "{\"summary\":\"S\",\"recommendations\":[\""
                + "y".repeat(CoachInsightParser.MAX_ITEM + 1) + "\"]}";
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("over-long");
    }

    private static String items(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "" : ",").append("\"item ").append(i).append("\"");
        }
        return sb.toString();
    }

    // ------------------------------------------------------- structural junk

    @Test
    @DisplayName("malformed JSON is rejected")
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{\"summary\": \"unterminated"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("a bare JSON array instead of an object is rejected")
    void rejectsTopLevelArray() {
        assertThatThrownBy(() -> parser.parse("[\"a\",\"b\"]"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    @DisplayName("an empty response is rejected")
    void rejectsEmptyResponse() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }

    @Test
    @DisplayName("a null response is rejected")
    void rejectsNullResponse() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }

    @Test
    @DisplayName("a JSON null body is rejected")
    void rejectsJsonNull() {
        assertThatThrownBy(() -> parser.parse("null"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }

    @Test
    @DisplayName("a markdown-fenced response is rejected rather than unwrapped")
    void rejectsFencedResponse() {
        // The prompt asks for no fences, but a model may add them. The parser must not strip them:
        // doing so would mean accepting any wrapper, which is how an injection payload dressed as
        // extra text would get through.
        assertThatThrownBy(() -> parser.parse("```json\n" + VALID + "\n```"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }

    @Test
    @DisplayName("unknown extra fields are ignored rather than propagated")
    void ignoresUnknownFields() {
        CoachInsightPayload p = parser.parse(
                "{\"summary\":\"S\",\"sneaky\":\"ignored\",\"nested\":{\"a\":[1,2]}}");
        assertThat(p.summary()).isEqualTo("S");
    }

    @Test
    @DisplayName("the validated payload is immutable")
    void payloadIsImmutable() {
        CoachInsightPayload p = parser.parse(VALID);
        assertThatThrownBy(() -> p.observations().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test
    @DisplayName("an object summary is rejected")
    void rejectsObjectSummary() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":{\"text\":\"hi\"}}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class);
    }
    @Test
    @DisplayName("a non-array observations field is rejected")
    void rejectsNonArrayObservations() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"S\",\"observations\":\"nope\"}"))
                .isInstanceOf(CoachInsightParser.InvalidCoachResponseException.class)
                .hasMessageContaining("observations");
    }
}
