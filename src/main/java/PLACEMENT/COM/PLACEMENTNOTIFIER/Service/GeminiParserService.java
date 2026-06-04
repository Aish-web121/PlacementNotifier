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
            String requestBody = gson.toJson(Map.of(
                    "contents", new Object[]{
                            Map.of("parts", new Object[]{
                                    Map.of("text", prompt)
                            })
                    },
                    "generationConfig", Map.of(
                            "temperature", 0.1,
                            "maxOutputTokens", 200
                    )
            ));

            String url = String.format(GEMINI_URL, model, apiKey);

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
            return GeminiResult.fallback(subject);
        }
    }

    // ─────────────────────────────────────────────
    // BUILD PROMPT
    // ─────────────────────────────────────────────
    private String buildPrompt(String subject, String body) {

        String truncatedBody = body != null && body.length() > 600
                ? body.substring(0, 600)
                : body;

        return """
                You are a placement email filter for engineering college students in India.
                
                You will receive emails forwarded by the college placement cell.
                Your job is to decide if this email announces an opportunity for students.
                
                ─── ALWAYS RELEVANT (relevant: true) ───
                - Job openings or campus placement drives by any company
                - Internship opportunities open to students
                - Hackathons or coding competitions open to students
                - Case competitions or product challenges open to students
                - RecruitSage job notification emails ("New Job Opportunity", "Dear Student")
                - Any email announcing an opportunity for the student BATCH (not one person)
                
                ─── NEVER RELEVANT (relevant: false) ───
                ONLY mark false if the email is clearly one of these:
                1. A reply from/to a SINGLE specific named student
                   (e.g. "Dear Avani", "Dear Rahul" — a real person's first name, not "Dear Student")
                2. A student replying to the placement cell
                3. A conversation thread where a student is writing back
                4. Pure logistics with zero opportunity (spam folder reminder, travel update)
                
                ─── COMPANY EXTRACTION RULES ───
                - The SENDER is always the college placement cell or RecruitSage — NEVER use sender as company
                - For RecruitSage emails: extract the company mentioned inside (e.g. "Honeywell")
                - For placement cell emails: extract the recruiting company from the body (e.g. "Goldman Sachs")
                - For hackathons: use the organizing company (e.g. "Goldman Sachs", "Tata Technologies")
                - If no company found: use "Unknown"
                
                ─── ROLE EXTRACTION RULES ───
                - For jobs/internships: extract the exact job title (e.g. "Data Scientist", "Software Engineer Intern")
                - For hackathons/competitions: use "Hackathon" or "Competition"
                - NEVER copy a full sentence from the email as the role
                - If unclear: use "Not specified"
                
                ─── TEST CASES TO LEARN FROM ───
                
                CASE 1 — RecruitSage job email:
                Subject: New Job Opportunity - Data Scientist at Honeywell
                Body: Dear Student, A new job opportunity has been posted... Data Scientist, Company: Honeywell
                Expected: relevant=true, company="Honeywell", role="Data Scientist"
                
                CASE 2 — Placement cell hackathon:
                Subject: Goldman Sachs India Hackathon 2026 - Launch
                Body: We are pleased to announce the 2026 Goldman Sachs India Hackathon (GSIH)...
                Expected: relevant=true, company="Goldman Sachs", role="Hackathon"
                
                CASE 3 — Student reply (NOT relevant):
                Subject: Re: Goldman Sachs India Hackathon 2026 - Launch
                Body: Dear AVANI, Ensure that you have checked your SPAM folder. Best, Placement Team. On Wed... AVANI SINGH wrote: Dear recruitment team...
                Expected: relevant=false
                
                ─── NOW ANALYZE THIS EMAIL ───
                
                Email Subject: %s
                Email Body: %s
                
                Respond in JSON only. No explanation. No markdown. Just JSON:
                {
                  "relevant": true or false,
                  "company": "company name or Unknown",
                  "role": "job title or Hackathon or Competition or Not specified",
                  "deadline": "deadline date or null"
                }
                """.formatted(subject, truncatedBody);
    }

    // ─────────────────────────────────────────────
    // PARSE GEMINI RESPONSE
    // ─────────────────────────────────────────────
    private GeminiResult parseGeminiResponse(String response) {
        try {
            JsonObject root = JsonParser.parseString(response)
                    .getAsJsonObject();

            String text = root
                    .getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

            text = text.trim()
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            log.debug("Gemini raw response: {}", text);

            JsonObject result = JsonParser.parseString(text)
                    .getAsJsonObject();

            boolean relevant = result.get("relevant").getAsBoolean();

            if (!relevant) {
                log.info("Gemini marked email as NOT relevant → skipping");
                return null;
            }

            String company  = getStringOrDefault(result, "company",  "Unknown");
            String role     = getStringOrDefault(result, "role",     "Not specified");
            String deadline = getStringOrNull(result, "deadline");

            log.info("Gemini extracted → Company: {} | Role: {} | Deadline: {}",
                    company, role, deadline);

            return new GeminiResult(company, role, deadline);

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            return GeminiResult.fallback("Unknown");
        }
    }

    private String getStringOrDefault(JsonObject obj, String key, String defaultVal) {
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
    // ─────────────────────────────────────────────
    public static class GeminiResult {
        public final String company;
        public final String role;
        public final String deadline;

        public GeminiResult(String company, String role, String deadline) {
            this.company  = company;
            this.role     = role;
            this.deadline = deadline;
        }

        public static GeminiResult fallback(String subject) {
            return new GeminiResult("Unknown", "Not specified", null);
        }
    }
}
