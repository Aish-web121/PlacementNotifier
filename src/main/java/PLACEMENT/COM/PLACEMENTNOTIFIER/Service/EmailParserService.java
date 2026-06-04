package PLACEMENT.COM.PLACEMENTNOTIFIER.Service;

import PLACEMENT.COM.PLACEMENTNOTIFIER.DTO.ParsedEmailDto;
import com.google.api.services.gmail.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailParserService {

    private final GmailService gmailService;
    private final GeminiParserService geminiParserService;

    // ─────────────────────────────────────────────
    // MAIN METHOD
    // Returns null if email is not relevant
    // ─────────────────────────────────────────────
    public ParsedEmailDto parse(Message message) {

        String subject = gmailService.getSubject(message);
        String sender  = gmailService.getSender(message);
        String body    = gmailService.getBody(message);

        log.info("Parsing email - Subject: {}", subject);

        // ── STEP 1: Ask Gemini if relevant + extract company/role ──
        GeminiParserService.GeminiResult gemini =
                geminiParserService.analyzeEmail(subject, body);

        // Gemini returned null → not relevant → skip this email
        if (gemini == null) {
            log.info("Email not relevant per Gemini → skipping: {}", subject);
            return null;
        }

        // ── STEP 2: Use Gemini's company & role ──
        // Only fall back to regex if Gemini returned "Unknown"
        String company = gemini.company;
        if (company == null || company.equalsIgnoreCase("Unknown")) {
            company = extractCompanyFallback(subject, body);
        }

        String role = gemini.role;
        if (role == null || role.equalsIgnoreCase("Not specified")) {
            role = extractRoleFallback(subject, body);
        }

        // ── STEP 3: Deadline and snippet (regex is fine for these) ──
        String deadline = gemini.deadline != null
                ? gemini.deadline
                : extractDeadline(body);

        String snippet     = createSnippet(body);
        String cleanSender = cleanSender(sender);

        return ParsedEmailDto.builder()
                .messageId(message.getId())
                .subject(subject)
                .sender(cleanSender)
                .company(company)
                .role(role)
                .deadline(deadline)
                .snippet(snippet)
                .build();
    }

    // ─────────────────────────────────────────────
    // COMPANY FALLBACK
    // Only used when Gemini returns "Unknown"
    // Does NOT use sender domain (avoids "Thapar" bug)
    // ─────────────────────────────────────────────
    private static final String[] KNOWN_COMPANIES = {
            "Google", "Microsoft", "Amazon", "Flipkart", "Infosys",
            "TCS", "Wipro", "HCL", "Accenture", "Cognizant",
            "Deloitte", "IBM", "Oracle", "Adobe", "Salesforce",
            "Altair", "Capgemini", "Tech Mahindra", "Zoho", "Freshworks",
            "Swiggy", "Zomato", "Paytm", "Razorpay", "Goldman Sachs",
            "PhonePe", "Ola", "Uber", "Meesho", "CRED",
            "Honeywell", "Persistent Systems", "Mphasis", "Hexaware",
            "L&T Technology", "Bajaj Finserv", "HDFC Bank",
            "Juspay", "Jupiter", "Groww", "Zerodha", "Tata Technologies",
            "Nagarro", "Mindtree", "NIIT", "Publicis Sapient"
    };

    private String extractCompanyFallback(String subject, String body) {

        String combined = (subject + " " + body).toLowerCase();

        // Strategy 1: known companies list
        for (String company : KNOWN_COMPANIES) {
            if (combined.contains(company.toLowerCase())) {
                return company;
            }
        }

        // Strategy 2: "Company: XYZ" label in body
        Pattern companyLabel = Pattern.compile(
                "(?i)(?:company|organisation|organization|employer|hiring company)" +
                        "\\s*:\\s*([A-Za-z0-9\\s&.-]+?)(?:\\n|,|\\.|$)"
        );
        Matcher labelMatcher = companyLabel.matcher(body);
        if (labelMatcher.find()) {
            return labelMatcher.group(1).trim();
        }

        // Strategy 3: "XYZ is hiring" in subject
        Pattern subjectCompany = Pattern.compile(
                "(?i)^([A-Z][a-zA-Z0-9\\s&.-]+?)\\s+" +
                        "(?:is hiring|hiring|recruitment|placement drive|off campus)"
        );
        Matcher subjectMatcher = subjectCompany.matcher(subject);
        if (subjectMatcher.find()) {
            return subjectMatcher.group(1).trim();
        }

        // NOTE: No sender domain fallback — that caused the "Thapar" bug
        return "Unknown";
    }

    // ─────────────────────────────────────────────
    // ROLE FALLBACK
    // Only used when Gemini returns "Not specified"
    // ─────────────────────────────────────────────
    private static final String[] ROLE_KEYWORDS = {
            "Software Engineer", "SDE", "Developer", "Intern",
            "Fresher", "Graduate Trainee", "Analyst", "Consultant",
            "Associate", "Trainee", "Engineer", "Programmer",
            "Data Scientist", "Data Analyst", "ML Engineer",
            "Backend", "Frontend", "Full Stack", "DevOps",
            "Product Manager", "VLSI", "Embedded",
            "RF Engineer", "Network Engineer", "System Engineer",
            "Researcher", "Quant", "Operations",
            "UI/UX", "Designer", "Tester", "QA",
            "Cloud", "Security", "iOS", "Android"
    };

    private String extractRoleFallback(String subject, String body) {

        // Strategy 1: "Role/Position: XYZ" label
        if (body != null) {
            Pattern roleLabel = Pattern.compile(
                    "(?i)(?:role|position|designation|profile|job title)" +
                            "\\s*:\\s*([A-Za-z0-9\\s&./-]+?)(?:\\n|,|\\.|$)"
            );
            Matcher labelMatcher = roleLabel.matcher(body);
            if (labelMatcher.find()) {
                return labelMatcher.group(1).trim();
            }
        }

        // Strategy 2: keyword scan
        String combined = subject + " " + (body != null ? body : "");
        for (String role : ROLE_KEYWORDS) {
            Pattern pattern = Pattern.compile("(?i)\\b" + role + "\\b");
            if (pattern.matcher(combined).find()) {
                return role;
            }
        }

        return "Not specified";
    }

    // ─────────────────────────────────────────────
    // DEADLINE EXTRACTION
    // ─────────────────────────────────────────────
    private String extractDeadline(String body) {
        if (body == null) return null;
        Pattern deadlinePattern = Pattern.compile(
                "(?i)(?:apply by|last date|deadline|closes?|due by|apply before)" +
                        "[:\\s]+([0-9]{1,2}[\\s/-]?(?:Jan|Feb|Mar|Apr|May|Jun|" +
                        "Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[\\s/-]?[0-9]{0,4})",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = deadlinePattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    // ─────────────────────────────────────────────
    // SENDER CLEANING
    // ─────────────────────────────────────────────
    private String cleanSender(String sender) {
        if (sender == null || sender.isEmpty()) return "Unknown";
        if (sender.contains("<")) {
            return sender.substring(0, sender.indexOf("<")).trim();
        }
        return sender;
    }

    // ─────────────────────────────────────────────
    // SNIPPET CREATION
    // ─────────────────────────────────────────────
    private String createSnippet(String body) {
        if (body == null || body.isEmpty()) return "No preview available";
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
