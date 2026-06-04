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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GeminiParserService {

    private final WebClient webClient;
    private final Gson gson;
    private final String apiKey;
    private final String model;

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private static final String[] NEVER_COMPANY = {
            "thapar", "tiet", "placements thapar", "placement cell",
            "recruitsage", "naukri", "linkedin", "internshala",
            "unstop", "dare2compete", "collegedunia", "shiksha"
    };

    // ── FIX: @Value moved into constructor — prevents Lookup method resolution failed ──
    public GeminiParserService(
            WebClient webClient,
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.model}") String model) {
        this.webClient = webClient;
        this.apiKey    = apiKey;
        this.model     = model;
        this.gson      = new Gson();
    }

    // ─────────────────────────────────────────────
    // MAIN METHOD
    // Returns null → email not relevant, skip it
    // Returns GeminiResult → email is relevant
    // ─────────────────────────────────────────────
    public GeminiResult analyzeEmail(String subject, String body) {

        if (shouldSkip(subject, body)) {
            return null;
        }

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
                            "maxOutputTokens", 256
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
                    .block(Duration.ofSeconds(20));

            return parseGeminiResponse(response);

        } catch (Exception e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            return GeminiResult.fallback();
        }
    }

    // ─────────────────────────────────────────────
    // PRE-FILTER
    // ─────────────────────────────────────────────
    private boolean shouldSkip(String subject, String body) {

        if (subject == null) subject = "";
        if (body == null) body = "";

        String subjectLower = subject.toLowerCase().trim();
        String bodyLower    = body.toLowerCase().trim();

        // 1. Reply emails
       // AFTER — only blocks Re: emails with no opportunity signal
if (subjectLower.startsWith("re:") || subjectLower.startsWith("re :")) {
    Set<String> opportunityKeywords = Set.of(
        "round 2", "round 3", "shortlisted", "selected", "next round",
        "qualified", "congratulations", "result", "interview", "offer",
        "internship", "placement", "hiring", "opportunity", "opening"
    );
    String combined = (subjectLower + " " + body.toLowerCase()).trim();
    boolean hasOpportunity = opportunityKeywords.stream()
            .anyMatch(combined::contains);

    if (!hasOpportunity) {
        log.info("PRE-FILTER: Skipping reply email → {}", subject);
        return true;
    }
    log.info("PRE-FILTER: Re: email has opportunity signal, passing to Gemini → {}", subject);
}

        // 2. Email addressed to a specific named student
        Pattern dearPattern = Pattern.compile(
                "dear\\s+([a-z]+)[,\\s]", Pattern.CASE_INSENSITIVE);
        Matcher matcher = dearPattern.matcher(bodyLower);
        if (matcher.find()) {
            String name = matcher.group(1).toLowerCase();
            Set<String> genericGreetings = Set.of(
                    "student", "students", "all", "team", "sir",
                    "madam", "faculty", "members", "candidate", "candidates",
                    "applicant", "applicants", "participant", "participants",
                    "recruiter", "recruiters"
            );
            if (!genericGreetings.contains(name)) {
                log.info("PRE-FILTER: Skipping email to specific person → Dear {}", name);
                return true;
            }
        }

        // 3. Spam reminders with no opportunity content
        if ((bodyLower.contains("check your spam") || bodyLower.contains("check you spam"))
                && !bodyLower.contains("apply")
                && !bodyLower.contains("opening")
                && !bodyLower.contains("opportunity")
                && !bodyLower.contains("internship")) {
            log.info("PRE-FILTER: Skipping spam reminder email");
            return true;
        }

        return false;
    }

    // ─────────────────────────────────────────────
    // BUILD PROMPT
    // ─────────────────────────────────────────────
    private String buildPrompt(String subject, String body) {

        String truncatedBody = body != null && body.length() > 1500
                ? body.substring(0, 1500)
                : body;

        return """
                You are a placement email classifier for engineering students in India.
                
                Your task:
                1. Decide if this email announces an opportunity for students
                2. Extract the HIRING company, role, and deadline
                
                ════════════════════════════════════
                RELEVANT = true (process these):
                ════════════════════════════════════
                - Job openings or campus placement drives
                - Internship opportunities open to students
                - Hackathons or coding competitions open to students
                - Case competitions or product challenges open to students
                - Any opportunity announced for the student batch
                
                ════════════════════════════════════
                RELEVANT = false (skip these):
                ════════════════════════════════════
                ONLY mark false if CLEARLY one of:
                - Reply from/to a single specific named student
                - A student writing back to the placement cell
                - Pure logistics with zero opportunity (travel, spam reminder)
                
                ════════════════════════════════════
                COMPANY EXTRACTION — CRITICAL RULES:
                ════════════════════════════════════
                - The EMAIL SENDER is always the college or intermediary — NEVER the company
                - FORBIDDEN companies (never use these): Thapar, TIET, Placements Thapar,
                  Placement Cell, RecruitSage, Naukri, LinkedIn, Internshala, Unstop
                - For RecruitSage emails → look inside body for "Company: XYZ"
                - For placement cell emails → find the recruiting company in the body
                - For hackathons → use the organizer (e.g. Goldman Sachs, Tata Technologies)
                - If genuinely no company found → return "Unknown"
                
                ════════════════════════════════════
                ROLE EXTRACTION — CRITICAL RULES:
                ════════════════════════════════════
                - Return ONLY a job title (e.g. "Data Scientist", "Software Engineer Intern")
                - For hackathons/competitions → return "Hackathon" or "Competition"
                - NEVER copy a full sentence from the email as the role
                - If unclear → return "Not specified"
                
                ════════════════════════════════════
                EXAMPLES — learn from these:
                ════════════════════════════════════
                
                EXAMPLE 1 — RecruitSage job email:
                Subject: New Job Opportunity!
                Body: Dear Student, A new job opportunity has been posted...
                      Data Scientist
                      Company: Honeywell
                      Log in to RecruitSage to view full details.
                Output: {"relevant": true, "company": "Honeywell", "role": "Data Scientist", "deadline": null}
                
                EXAMPLE 2 — Placement cell hackathon:
                Subject: Goldman Sachs India Hackathon 2026 - Launch
                Body: We are pleased to announce the 2026 Goldman Sachs India Hackathon (GSIH).
                      The application window closes on Sunday, May 10.
                Output: {"relevant": true, "company": "Goldman Sachs", "role": "Hackathon", "deadline": "May 10"}
                
                EXAMPLE 3 — Student reply (skip):
                Subject: Re: Goldman Sachs India Hackathon 2026 - Launch
                Body: Dear AVANI, Ensure that you have checked your SPAM folder.
                      Best, Placement Team. On Wed... AVANI SINGH wrote: Dear recruitment team...
                Output: {"relevant": false, "company": null, "role": null, "deadline": null}
                
                EXAMPLE 4 — Placement cell job drive:
                Subject: Microsoft Campus Recruitment Drive 2026
                Body: Dear Students, Microsoft is visiting our campus for placements.
                      Role: Software Engineer. Last date to apply: 15 Jun.
                Output: {"relevant": true, "company": "Microsoft", "role": "Software Engineer", "deadline": "15 Jun"}
                
                EXAMPLE 5 — Generic placement cell forward with no company:
                Subject: Placement Update
                Body: Dear Students, please ensure your resume is updated on the portal.
                Output: {"relevant": false, "company": null, "role": null, "deadline": null}
                
                ════════════════════════════════════
                NOW ANALYZE THIS EMAIL:
                ════════════════════════════════════
                Subject: %s
                Body: %s
                
                Respond ONLY with a single JSON object. No explanation. No markdown. No extra text.
                {
                  "relevant": true or false,
                  "company": "company name or Unknown or null",
                  "role": "job title or Hackathon or Competition or Not specified or null",
                  "deadline": "deadline date or null"
                }
                """.formatted(subject, truncatedBody);
    }

    // ─────────────────────────────────────────────
    // PARSE GEMINI RESPONSE
    // ─────────────────────────────────────────────
    private GeminiResult parseGeminiResponse(String response) {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();

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

            JsonObject result = JsonParser.parseString(text).getAsJsonObject();

            boolean relevant = result.get("relevant").getAsBoolean();

            if (!relevant) {
                log.info("Gemini → NOT relevant, skipping email");
                return null;
            }

            String company  = getStringOrDefault(result, "company",  "Unknown");
            String role     = getStringOrDefault(result, "role",     "Not specified");
            String deadline = getStringOrNull(result, "deadline");

            for (String forbidden : NEVER_COMPANY) {
                if (company.toLowerCase().contains(forbidden)) {
                    log.warn("Gemini returned forbidden company '{}' → resetting to Unknown", company);
                    company = "Unknown";
                    break;
                }
            }

            log.info("Gemini → Company: {} | Role: {} | Deadline: {}",
                    company, role, deadline);

            return new GeminiResult(company, role, deadline);

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            return GeminiResult.fallback();
        }
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────
    private String getStringOrDefault(JsonObject obj, String key, String defaultVal) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String val = obj.get(key).getAsString().trim();
                if (!val.isEmpty() && !val.equalsIgnoreCase("null")) {
                    return val;
                }
            }
        } catch (Exception ignored) {}
        return defaultVal;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String val = obj.get(key).getAsString().trim();
                if (!val.isEmpty() && !val.equalsIgnoreCase("null")) {
                    return val;
                }
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

        public static GeminiResult fallback() {
            return new GeminiResult("Unknown", "Not specified", null);
        }
    }
}
