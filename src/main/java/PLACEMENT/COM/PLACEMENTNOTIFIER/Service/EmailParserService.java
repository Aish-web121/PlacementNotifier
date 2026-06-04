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

    private static final String[] KNOWN_COMPANIES = {
            "Google", "Microsoft", "Amazon", "Flipkart", "Infosys",
            "TCS", "Wipro", "HCL", "Accenture", "Cognizant",
            "Deloitte", "IBM", "Oracle", "Adobe", "Salesforce",
            "Altair", "Capgemini", "Tech Mahindra", "Zoho", "Freshworks",
            "Swiggy", "Zomato", "Paytm", "BYJU'S", "Razorpay",
            "PhonePe", "Ola", "Uber", "Meesho", "CRED",
            "Honeywell", "Persistent Systems", "Mphasis", "Hexaware",
            "L&T Technology", "Bajaj Finserv", "HDFC Bank",
            "Juspay", "Jupiter", "Groww", "slice", "Zerodha",
            "Nagarro", "Mindtree", "NIIT", "Publicis Sapient"
    };

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

    // ─────────────────────────────────────────────
    // MAIN METHOD
    // ─────────────────────────────────────────────
    public ParsedEmailDto parse(Message message) {

        String subject  = gmailService.getSubject(message);
        String sender   = gmailService.getSender(message);
        String body     = gmailService.getBody(message); // already clean structured text

        // NO htmlToText() call needed here anymore
        // GmailService already returns structured plain text

        log.info("Parsing email - Subject: {}", subject);

        String cleanSender = cleanSender(sender);
        String company     = extractCompany(subject, body, sender);
        String role        = extractRole(subject, body);
        String deadline    = extractDeadline(body);
        String snippet     = createSnippet(body);

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
    // HTML TO TEXT
    // Preserves structure by converting block tags to newlines
    // BEFORE stripping all tags
    // ─────────────────────────────────────────────

    // ─────────────────────────────────────────────
    // COMPANY EXTRACTION
    // Strategy 1: known companies list
    // Strategy 2: "Company/Organisation: XYZ" label
    // Strategy 3: "XYZ is hiring" in subject
    // Strategy 4: sender domain
    // Strategy 5: Unknown
    // ─────────────────────────────────────────────
    private String extractCompany(String subject, String body, String sender) {

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

        // Strategy 4: sender domain
        Pattern emailPattern = Pattern.compile("@([a-zA-Z0-9.-]+)\\.");
        Matcher emailMatcher = emailPattern.matcher(sender);
        if (emailMatcher.find()) {
            String domain = emailMatcher.group(1);
            if (!domain.equalsIgnoreCase("gmail")
                    && !domain.equalsIgnoreCase("yahoo")
                    && !domain.equalsIgnoreCase("outlook")
                    && !domain.equalsIgnoreCase("hotmail")
                    && !domain.equalsIgnoreCase("recruitsage")
                    && !domain.equalsIgnoreCase("naukri")
                    && !domain.equalsIgnoreCase("linkedin")
                    && !domain.equalsIgnoreCase("internshala")) {
                return domain.substring(0, 1).toUpperCase() + domain.substring(1);
            }
        }

        // Strategy 5: give up
        return "Unknown";
    }

    // ─────────────────────────────────────────────
    // ROLE EXTRACTION
    // Strategy 1: scan lines — find line just before "Company:"
    // Strategy 2: "Role/Position/Designation: XYZ" label
    // Strategy 3: "XYZ Opening/Opportunity" in subject
    // Strategy 4: keyword scan
    // Strategy 5: Not specified
    // ─────────────────────────────────────────────
    private String extractRole(String subject, String body) {

        // Strategy 1: scan body lines
        // RecruitSage pattern:
        //   "Data Scientist"      ← role line
        //   "Company: Honeywell"  ← company line right after
        // OR
        //   "Post Graduate Trainee-RF, Electromagnetics..."  ← role is first meaningful line
        if (body != null && !body.isEmpty()) {
            String[] lines = body.split("\\n");

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();

                // skip empty lines
                if (line.isEmpty()) continue;

                // skip filler lines
                if (isFillerLine(line)) continue;

                // BEST SIGNAL: next line starts with "Company:"
                // means THIS line is the role
                if (i + 1 < lines.length) {
                    String nextLine = lines[i + 1].trim();
                    if (nextLine.toLowerCase().startsWith("company")) {
                        if (line.contains(",")) {
                            line = line.substring(0, line.indexOf(",")).trim();
                        }
                        return line;
                    }
                }
            }

            // fallback: first non-filler short line is probably the role
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (isFillerLine(line)) continue;
                if (line.length() > 3 && line.length() < 60) {
                    if (line.contains(",")) {
                        line = line.substring(0, line.indexOf(",")).trim();
                    }
                    return line;
                }
            }
        }

        // Strategy 2: "Role/Position/Designation: XYZ" label
        Pattern roleLabel = Pattern.compile(
                "(?i)(?:role|position|designation|profile|job title)" +
                        "\\s*:\\s*([A-Za-z0-9\\s&./-]+?)(?:\\n|,|\\.|$)"
        );
        Matcher labelMatcher = roleLabel.matcher(body);
        if (labelMatcher.find()) {
            return labelMatcher.group(1).trim();
        }

        // Strategy 3: "XYZ Opening/Opportunity" in subject
        Pattern subjectRole = Pattern.compile(
                "(?i)^([A-Za-z0-9\\s&/-]+?)\\s+" +
                        "(?:opening|opportunity|position|role|vacancy|hiring|drive)"
        );
        Matcher subjectMatcher = subjectRole.matcher(subject);
        if (subjectMatcher.find()) {
            return subjectMatcher.group(1).trim();
        }

        // Strategy 4: keyword scan
        String combined = subject + " " + body;
        for (String role : ROLE_KEYWORDS) {
            Pattern pattern = Pattern.compile("(?i)\\b" + role + "\\b");
            Matcher matcher = pattern.matcher(combined);
            if (matcher.find()) {
                return matcher.group();
            }
        }

        // Strategy 5: nothing found
        return "Not specified";
    }

    // ─────────────────────────────────────────────
    // HELPER: is this line a filler/greeting?
    // ─────────────────────────────────────────────
    private boolean isFillerLine(String line) {
        String lower = line.toLowerCase();
        return lower.startsWith("dear")
                || lower.startsWith("hi ")
                || lower.startsWith("hello")
                || lower.startsWith("greetings")
                || lower.startsWith("a new")
                || lower.startsWith("we are")
                || lower.startsWith("please")
                || lower.startsWith("log in")
                || lower.startsWith("click")
                || lower.startsWith("this email")
                || lower.startsWith("if you have")
                || lower.startsWith("view job")
                || lower.contains("@")
                || lower.contains("http")
                || lower.startsWith("new opportunity")
                || lower.contains("unsubscribe")
                ||lower.contains("recruitsage")
                || line.length() > 80;
    }

    // ─────────────────────────────────────────────
    // DEADLINE EXTRACTION
    // ─────────────────────────────────────────────
    private String extractDeadline(String body) {
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
        if (cleaned.length() > 200) {
            return cleaned.substring(0, 200) + "...";
        }
        return cleaned;
    }
}