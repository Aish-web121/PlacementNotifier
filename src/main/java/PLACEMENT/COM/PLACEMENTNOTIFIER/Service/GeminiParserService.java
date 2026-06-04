package PLACEMENT.COM.PLACEMENTNOTIFIER.Service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@Service
@Slf4j
public class GeminiParserService {

    private final WebClient webClient;
    private final Gson gson;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    public GeminiParserService(WebClient webClient) {
        this.webClient = webClient;
        this.gson = new Gson();
    }

    // ─────────────────────────────────────────────
    // MAIN METHOD
    // Returns null if email is NOT relevant
    // Returns GeminiResult if email IS relevant
    // ─────────────────────────────────────────────
    public GeminiResult analyzeEmail(String subject, String body) {

        String prompt = buildPrompt(subject, body);

        try {
            // Build Gemini request body
            String requestBody = gson.toJson(Map.of(
                    "contents", new Object[]{
                            Map.of("parts", new Object[]{
                                    Map.of("text", prompt)
                            })
                    },
                    "generationConfig", Map.of(
                            "temperature", 0.1,      // low = consistent output
                            "maxOutputTokens", 200   // we only need small JSON
                    )
            ));

            String url = String.format(GEMINI_URL, model, apiKey);

            // Call Gemini API
            String response = webClient
                    .post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            return parseGeminiResponse(response);

        } catch (Exception e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            // If Gemini fails → fallback: treat as relevant
            // Better to send extra notification than miss one
            return GeminiResult.fallback(subject);
        }
    }

    // ─────────────────────────────────────────────
    // BUILD PROMPT
    // ─────────────────────────────────────────────
    private String buildPrompt(String subject, String body) {

        // Truncate body to save tokens
        String truncatedBody = body != null && body.length() > 500
                ? body.substring(0, 500)
                : body;

        return """
                You are a placement email filter for a college student in India.
                
                Analyze this email and determine if it is a GENERAL placement,
                job, internship, or hackathon announcement meant for ALL students.
                
                RELEVANT emails (process these):
                - Job openings from companies
                - Internship opportunities
                - Hackathon announcements
                - Campus recruitment drives
                - General placement notices for all students
                
                NOT RELEVANT emails (ignore these):
                - Personal replies to specific students ("Dear Avani...")
                - Logistics/travel updates ("travel back on 27th July")
                - Spam folder reminders
                - General college notices unrelated to jobs
                - Reply chains between students and placement cell
                - Emails addressed to ONE specific person by name
                
                Email Subject: %s
                Email Body: %s
                
                Respond in JSON only. No explanation. No markdown. Just JSON:
                {
                  "relevant": true or false,
                  "company": "company name or Unknown",
                  "role": "job role or Not specified",
                  "deadline": "deadline date or null"
                }
                """.formatted(subject, truncatedBody);
    }

    // ─────────────────────────────────────────────
    // PARSE GEMINI RESPONSE
    // ─────────────────────────────────────────────
    private GeminiResult parseGeminiResponse(String response) {
        try {
            // Extract text from Gemini response structure
            JsonObject root = JsonParser.parseString(response)
                    .getAsJsonObject();

            String text = root
                    .getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

            // Clean up response
            // Sometimes Gemini adds ```json ``` around it
            text = text.trim()
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            log.debug("Gemini raw response: {}", text);

            // Parse the JSON Gemini returned
            JsonObject result = JsonParser.parseString(text)
                    .getAsJsonObject();

            boolean relevant = result.get("relevant").getAsBoolean();

            if (!relevant) {
                log.info("Gemini marked email as NOT relevant → skipping");
                return null;
            }

            String company = getStringOrDefault(
                    result, "company", "Unknown");
            String role = getStringOrDefault(
                    result, "role", "Not specified");
            String deadline = getStringOrNull(result, "deadline");

            log.info("Gemini extracted → Company: {} | Role: {} | Deadline: {}",
                    company, role, deadline);

            return new GeminiResult(company, role, deadline);

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            // fallback on parse error
            return GeminiResult.fallback("Unknown");
        }
    }

    private String getStringOrDefault(JsonObject obj,
                                      String key,
                                      String defaultVal) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String val = obj.get(key).getAsString().trim();
                return val.isEmpty() ? defaultVal : val;
            }
        } catch (Exception ignored) {}
        return defaultVal;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String val = obj.get(key).getAsString().trim();
                return val.isEmpty() || val.equals("null") ? null : val;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────
    // RESULT CLASS
    // Holds what Gemini extracted
    // null = email not relevant, skip it
    // ─────────────────────────────────────────────
    public static class GeminiResult {
        public final String company;
        public final String role;
        public final String deadline;

        public GeminiResult(String company,
                            String role,
                            String deadline) {
            this.company = company;
            this.role = role;
            this.deadline = deadline;
        }

        // Used when Gemini fails
        // Better to notify than miss
        public static GeminiResult fallback(String subject) {
            return new GeminiResult("Unknown", "Not specified", null);
        }
    }
}
