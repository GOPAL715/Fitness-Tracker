package com.fittrack.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible provider.
 *
 * <p>Two models are configured. The <b>vision</b> model serves food scanning, which needs an image
 * input. The <b>text</b> model serves the Coach, which is text only; it previously ran on the vision
 * model, which wasted image-capable capacity and ignored {@code app.ai-text-model} entirely.
 *
 * <p>Each call carries its own timeout so a slow interactive Coach request cannot inherit the
 * scanner's longer budget, and each declares a max output token count so a response size is chosen
 * deliberately rather than left to the provider.
 */
@Component
public class OpenAiProvider implements AiProvider {
    private final String key;
    private final String visionModel;
    private final String textModel;
    private final RestClient client;
    private final RestClient coachClient;
    private final ObjectMapper mapper;
    private final Duration coachTimeout;

    @Autowired
    public OpenAiProvider(@Value("${app.ai-key}") String key, @Value("${app.ai-base-url}") String baseUrl,
            @Value("${app.ai-vision-model}") String visionModel,
            @Value("${app.ai-text-model:${app.ai-vision-model}}") String textModel,
            @Value("${app.ai-coach-timeout-ms:20000}") long coachTimeoutMs) {
        this(key, visionModel, textModel, client(baseUrl), coachClient(baseUrl, coachTimeoutMs), new ObjectMapper(),
                Duration.ofMillis(coachTimeoutMs));
    }

    OpenAiProvider(String key, String visionModel, String textModel, RestClient client, RestClient coachClient,
            ObjectMapper mapper, Duration coachTimeout) {
        this.key = key == null ? "" : key;
        this.visionModel = visionModel;
        this.textModel = textModel == null || textModel.isBlank() ? visionModel : textModel;
        this.client = client;
        this.coachClient = coachClient;
        this.mapper = mapper;
        this.coachTimeout = coachTimeout;
    }

    /**
     * The shared client, used by the food scanner.
     *
     * <p>Deliberately unchanged: the scanner uploads an image, so it keeps the longer budget and
     * nothing in Phase 9 narrows it.
     */
    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(45));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * A second client for the Coach, with the interactive budget actually wired to the socket.
     *
     * <p>This is a real read timeout on a real request factory, not a post-hoc elapsed-time check, so
     * a slow provider is abandoned at {@code ai-coach-timeout-ms} instead of tying up a request thread
     * for the scanner's 45 seconds. A second client is used rather than a mutated shared one because
     * the timeout is a property of the connection factory: sharing it would either slow the scanner
     * down or leave the Coach unprotected. Two clients over the same base URL and key is the smallest
     * change that keeps both behaviours correct, and it needs no new dependency.
     *
     * <p>The connect timeout is capped at the same budget so a slow connect cannot outlive the
     * request budget either.
     */
    private static RestClient coachClient(String baseUrl, long coachTimeoutMs) {
        long bounded = Math.max(1L, coachTimeoutMs);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(10_000L, bounded)));
        factory.setReadTimeout(Duration.ofMillis(bounded));
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
            return new FoodAnalysis(visionModel, foods.stream().limit(12).toList(), providerUsage(response, "openai-compatible", visionModel));
        } catch (AiUnavailableException e) { throw e;
        } catch (Exception e) { throw new AiUnavailableException("Food analysis provider is unavailable"); }
    }

    /** Deliberate ceiling on Coach output; the scanner keeps the provider default. */
    static final int MAX_COACH_OUTPUT_TOKENS = 800;

    private ProviderUsage providerUsage(JsonNode response, String provider, String model) {
        JsonNode u = response == null ? null : response.path("usage");
        if (u == null) return new ProviderUsage(provider, model, null, null);
        Integer in = u.path("prompt_tokens").isMissingNode() ? null : u.path("prompt_tokens").asInt();
        Integer out = u.path("completion_tokens").isMissingNode() ? null : u.path("completion_tokens").asInt();
        return new ProviderUsage(provider, model, in, out);
    }

    @Override
    public String analyzeCoach(String context) { return analyzeCoachWithMetadata(context).text(); }

    /**
     * Runs the Coach on the configured <b>text</b> model.
     *
     * <p>Requests {@code response_format: json_object}, the same mechanism the food scanner already
     * proves against this provider, so no new mechanism is introduced. The result is returned as raw
     * text for the caller to validate strictly: this class does not decide what a usable Coach
     * answer is, and it never logs or returns the provider payload on failure.
     */
    @Override
    public CoachAnalysis analyzeCoachWithMetadata(String context) {
        return analyzeCoachMessages(List.of(new CoachMessage("system", context)));
    }

    /**
     * Runs the Coach over an explicit message list.
     *
     * <p>Taking the messages rather than one flat string is what keeps the system instruction, the
     * untrusted user question and the server-generated context in separate provider messages.
     */
    public CoachAnalysis analyzeCoachMessages(List<CoachMessage> messages) {
        if (key.isBlank()) throw new AiUnavailableException("Coach AI is not configured");
        try {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (CoachMessage m : messages) {
                payload.add(Map.of("role", m.role(), "content", m.content()));
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", textModel);
            body.put("temperature", 0);
            body.put("max_tokens", MAX_COACH_OUTPUT_TOKENS);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", payload);

            // The Coach uses its own client, so this call really is bounded by the configured
            // interactive budget and not by the scanner's 45 second read timeout.
            JsonNode response = coachClient.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);

            if (response == null) {
                throw new AiProviderException("provider returned an empty response", "malformed");
            }
            JsonNode content = response.at("/choices/0/message/content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                throw new AiProviderException("provider response contained no content", "malformed");
            }
            return new CoachAnalysis(textModel, content.asText(),
                    providerUsage(response, "openai-compatible", textModel));
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw categorise(e);
        }
    }

    /**
     * Maps a transport failure onto a stable category.
     *
     * <p>The provider status code is the useful signal and is kept in the category, so a bad key is
     * distinguishable from a rate limit and from an outage. Neither the response body nor any
     * credential is propagated to the client or written to a log message.
     */
    private AiProviderException categorise(Exception e) {
        if (isTimeout(e)) return new AiProviderException("provider request timed out", "timeout");
        if (e instanceof RestClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 401 || status == 403) {
                return new AiProviderException("provider rejected the configured credentials", "configuration");
            }
            if (status == 429) {
                return new AiProviderException("provider rate limit reached", "provider_rate_limited");
            }
            if (status >= 500) {
                return new AiProviderException("provider returned a server error", "provider");
            }
            if (status >= 400) {
                return new AiProviderException("provider rejected the request", "provider_request");
            }
        }
        return new AiUnavailableException("Coach AI provider is unavailable");
    }

    /** Read and connect timeouts surface as a SocketTimeoutException or a wrapped I/O timeout. */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException) return true;
            if (t instanceof java.io.InterruptedIOException) return true;
            if (t instanceof org.springframework.web.client.ResourceAccessException
                    && t.getMessage() != null && t.getMessage().toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    /** The interactive budget actually applied to the Coach's transport, for tests and diagnostics. */
    Duration coachTimeout() {
        return coachTimeout;
    }

    /**
     * Builds a provider whose two clients really carry their own timeouts.
     *
     * <p>Package-private and used only by tests, so a timeout assertion exercises the same
     * {@link #client} and {@link #coachClient} factories production uses rather than an injected
     * stand-in. Without this the timeout could be "configured" only in the Spring constructor and
     * left at some default in the other path, which is the exact defect this exists to catch.
     */
    static OpenAiProvider forBaseUrl(String baseUrl, long coachTimeoutMs) {
        return new OpenAiProvider("test-key", "vision-model", "text-model",
                client(baseUrl), coachClient(baseUrl, coachTimeoutMs), new ObjectMapper(),
                Duration.ofMillis(Math.max(1L, coachTimeoutMs)));
    }

    public static class AiUnavailableException extends AiProviderException {
        public AiUnavailableException(String message) { super(message, "provider"); }
    }
}
