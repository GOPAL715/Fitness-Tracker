package com.fittrack.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiProvider implements AiProvider {
    private final String key;
    private final String visionModel;
    private final RestClient client;
    private final ObjectMapper mapper;

    public OpenAiProvider(@Value("${app.ai-key}") String key, @Value("${app.ai-base-url}") String baseUrl,
            @Value("${app.ai-vision-model}") String visionModel) {
        this(key, visionModel, client(baseUrl), new ObjectMapper());
    }

    OpenAiProvider(String key, String visionModel, RestClient client, ObjectMapper mapper) {
        this.key = key == null ? "" : key;
        this.visionModel = visionModel;
        this.client = client;
        this.mapper = mapper;
    }

    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(45));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public FoodAnalysis analyzeFood(byte[] imageBytes, String contentType) {
        if (key.isBlank()) throw new AiUnavailableException("Food scanning is not configured");
        String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        try {
            Map<String, Object> image = Map.of("type", "image_url", "image_url", Map.of("url", dataUrl));
            Map<String, Object> content = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "Identify visible food. Return JSON {items:[{name,grams,confidence}]}. Grams must be positive; confidence must be 0..1. Do not return nutrition."),
                image));
            JsonNode response = client.post().uri("/chat/completions").header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", visionModel, "temperature", 0, "response_format", Map.of("type", "json_object"), "messages", List.of(content)))
                .retrieve().body(JsonNode.class);
            JsonNode contentNode = response == null ? null : response.at("/choices/0/message/content");
            JsonNode parsed = contentNode == null ? null : mapper.readTree(contentNode.asText()).path("items");
            if (parsed == null || !parsed.isArray()) throw new AiUnavailableException("Food analysis returned an invalid response");
            List<FoodItem> foods = new ArrayList<>();
            for (JsonNode item : parsed) {
                String name = item.path("name").asText("").trim();
                double grams = item.path("grams").asDouble(-1);
                double confidence = item.path("confidence").asDouble(-1);
                if (!name.isBlank() && name.length() <= 160 && grams > 0 && grams <= 5000 && confidence >= 0 && confidence <= 1)
                    foods.add(new FoodItem(name, grams, confidence));
            }
            if (foods.isEmpty()) throw new AiUnavailableException("No food could be identified in the image");
            return new FoodAnalysis(visionModel, foods.stream().limit(12).toList());
        } catch (AiUnavailableException e) { throw e;
        } catch (Exception e) { throw new AiUnavailableException("Food analysis provider is unavailable"); }
    }

    @Override
    public String analyzeCoach(String context) {
        if (key.isBlank()) throw new AiUnavailableException("Coach AI is not configured");
        try {
            JsonNode response = client.post().uri("/chat/completions").header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", visionModel, "messages", List.of(Map.of("role", "user", "content", context))))
                .retrieve().body(JsonNode.class);
            return response.at("/choices/0/message/content").asText();
        } catch (Exception e) { throw new AiUnavailableException("Coach AI provider is unavailable"); }
    }

    public static class AiUnavailableException extends IllegalStateException {
        public AiUnavailableException(String message) { super(message); }
    }
}