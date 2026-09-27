package com.fittrack.app;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppDataServiceTest {
    @Test
    void rejectsMissingPrincipal() {
        AppDataService service = new AppDataService(new JdbcTemplate());
        assertThrows(IllegalArgumentException.class, () -> service.load(null));
    }

    private static Map<String,Object> exerciseRow(String secondaryMuscles) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", "11111111-1111-1111-1111-111111111111");
        row.put("name", "Bench Press");
        row.put("muscle_group", "Chest");
        row.put("secondary_muscles", secondaryMuscles);
        return row;
    }

    @Test
    void splitsACommaSeparatedValueIntoAList() {
        List<Map<String,Object>> result = AppDataService.withSecondaryMuscles(
                List.of(exerciseRow("Biceps,Triceps")));

        assertEquals(List.of("Biceps", "Triceps"), result.get(0).get("secondary_muscles"));
    }

    @Test
    void returnsAnEmptyListForNull() {
        List<Map<String,Object>> result = AppDataService.withSecondaryMuscles(
                List.of(exerciseRow(null)));

        assertEquals(List.of(), result.get(0).get("secondary_muscles"));
    }

    @Test
    void returnsAnEmptyListForAnEmptyString() {
        assertEquals(List.of(), AppDataService.splitCsv(""));
        assertEquals(List.of(), AppDataService.splitCsv("   "));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals(List.of("Biceps", "Triceps"), AppDataService.splitCsv(" Biceps , Triceps "));
    }

    @Test
    void dropsEmptyElements() {
        // A stray or trailing comma must not surface as an empty badge in the UI.
        assertEquals(List.of("Biceps"), AppDataService.splitCsv("Biceps,,  ,"));
    }

    @Test
    void keepsASingleValueAsAOneElementList() {
        assertEquals(List.of("Biceps"), AppDataService.splitCsv("Biceps"));
    }

    @Test
    void normalisesEveryRowAndLeavesTheSourceUntouched() {
        Map<String,Object> first = exerciseRow("Biceps,Triceps");
        Map<String,Object> second = exerciseRow(null);
        List<Map<String,Object>> source = List.of(first, second);

        List<Map<String,Object>> result = AppDataService.withSecondaryMuscles(source);

        assertEquals(2, result.size());
        assertEquals(List.of("Biceps", "Triceps"), result.get(0).get("secondary_muscles"));
        assertEquals(List.of(), result.get(1).get("secondary_muscles"));
        // The originals are untouched, so the read-only result is never aliased.
        assertEquals("Biceps,Triceps", first.get("secondary_muscles"));
        assertNotSame(first, result.get(0));
    }

    @Test
    void handlesAnEmptyCatalog() {
        assertEquals(List.of(), AppDataService.withSecondaryMuscles(List.of()));
    }
}
