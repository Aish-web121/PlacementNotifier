package PLACEMENT.COM.PLACEMENTNOTIFIER.Service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GmailService {

    private final Gmail gmail;

    private static final String SEARCH_QUERY =
            "from:recruitsage newer_than:20d";

    private static final int MAX_RESULTS = 10;

    // ─────────────────────────────────────────────
    // MAIN METHOD: called by scheduler every 3 min
    // ─────────────────────────────────────────────
    public List<Message> fetchPlacementEmails() {
        List<Message> result = new ArrayList<>();

        try {
            log.info("Fetching emails from Gmail...");

            ListMessagesResponse response = gmail
                    .users()
                    .messages()
                    .list("me")
                    .setQ(SEARCH_QUERY)
                    .setMaxResults((long) MAX_RESULTS)
                    .execute();

            if (response.getMessages() == null || response.getMessages().isEmpty()) {
                log.info("No new placement emails found.");
                return result;
            }

            log.info("Found {} potential placement emails", response.getMessages().size());

            for (Message msg : response.getMessages()) {
                try {
                    Message fullMessage = gmail
                            .users()
                            .messages()
                            .get("me", msg.getId())
                            .setFormat("FULL")
                            .execute();

                    result.add(fullMessage);

                } catch (IOException e) {
                    log.warn("Failed to fetch message {}: {}", msg.getId(), e.getMessage());
                }
            }

        } catch (IOException e) {
            log.error("Failed to fetch emails from Gmail: {}", e.getMessage());
        }

        return result;
    }

    // ─────────────────────────────────────────────
    // HELPER: Get Subject
    // ─────────────────────────────────────────────
    public String getSubject(Message message) {
        return getHeader(message, "Subject");
    }

    // ─────────────────────────────────────────────
    // HELPER: Get Sender
    // ─────────────────────────────────────────────
    public String getSender(Message message) {
        return getHeader(message, "From");
    }

    // ─────────────────────────────────────────────
    // HELPER: Get any header by name
    // ─────────────────────────────────────────────
    private String getHeader(Message message, String headerName) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return "Unknown";
        }

        return message.getPayload().getHeaders()
                .stream()
                .filter(h -> headerName.equalsIgnoreCase(h.getName()))
                .findFirst()
                .map(MessagePartHeader::getValue)
                .orElse("Unknown");
    }

    // ─────────────────────────────────────────────
    // HELPER: Get email body
    // Returns structured plain text (not raw HTML)
    // Structure is preserved so EmailParserService
    // can correctly identify role and company
    // ─────────────────────────────────────────────
    public String getBody(Message message) {
        try {
            MessagePart payload = message.getPayload();
            if (payload == null) return "";

            // Case 1: Simple plain text email
            // body is directly in payload, no parts
            if (payload.getBody() != null && payload.getBody().getData() != null) {
                return decodeBase64(payload.getBody().getData());
            }

            // Case 2: Multipart email
            // has multiple parts — plain text + HTML + maybe attachments
            if (payload.getParts() != null) {

                // First choice: plain text version
                // already has structure, no HTML to deal with
                for (MessagePart part : payload.getParts()) {
                    if ("text/plain".equals(part.getMimeType())
                            && part.getBody() != null
                            && part.getBody().getData() != null) {
                        return decodeBase64(part.getBody().getData());
                    }
                }

                // Second choice: HTML version
                // convert to structured text using htmlToText()
                // NOT a blind tag strip — preserves newlines
                for (MessagePart part : payload.getParts()) {
                    if ("text/html".equals(part.getMimeType())
                            && part.getBody() != null
                            && part.getBody().getData() != null) {
                        String html = decodeBase64(part.getBody().getData());
                        return htmlToText(html); // ← smart conversion
                    }
                }

                // Case 3: nested multipart
                // some emails have parts inside parts
                // e.g. multipart/mixed → multipart/alternative → text/html
                for (MessagePart part : payload.getParts()) {
                    if (part.getMimeType() != null
                            && part.getMimeType().startsWith("multipart")
                            && part.getParts() != null) {

                        // look inside the nested part
                        for (MessagePart nestedPart : part.getParts()) {
                            if ("text/plain".equals(nestedPart.getMimeType())
                                    && nestedPart.getBody() != null
                                    && nestedPart.getBody().getData() != null) {
                                return decodeBase64(nestedPart.getBody().getData());
                            }
                        }
                        for (MessagePart nestedPart : part.getParts()) {
                            if ("text/html".equals(nestedPart.getMimeType())
                                    && nestedPart.getBody() != null
                                    && nestedPart.getBody().getData() != null) {
                                String html = decodeBase64(nestedPart.getBody().getData());
                                return htmlToText(html);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Could not extract email body: {}", e.getMessage());
        }

        return "";
    }

    // ─────────────────────────────────────────────
    // HELPER: Convert HTML to structured plain text
    //
    // OLD WAY (bad):
    //   html.replaceAll("<[^>]+>", " ")
    //   → destroys all structure
    //   → "Dear Student, A new job Data Scientist Company: Honeywell..."
    //   → everything on one line, parser can't find anything
    //
    // NEW WAY (good):
    //   block tags → newlines FIRST
    //   then strip remaining tags
    //   → structure preserved
    //   → "Dear Student,\n\nData Scientist\nCompany: Honeywell\n..."
    //   → parser can correctly identify role and company
    // ─────────────────────────────────────────────
    private String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";

        return html
                // block elements become newlines — preserve email structure
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)<p[^>]*>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)<div[^>]*>", "\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)<tr[^>]*>", "\n")
                .replaceAll("(?i)</tr>", "\n")
                .replaceAll("(?i)<td[^>]*>", " ")
                .replaceAll("(?i)<li[^>]*>", "\n- ")
                // strip ALL remaining html tags
                .replaceAll("<[^>]+>", "")
                // decode common HTML entities
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                // clean up whitespace
                .replaceAll("[ \\t]+", " ")          // multiple spaces → one space
                .replaceAll("\\n[ \\t]+", "\n")       // spaces after newline → gone
                .replaceAll("\\n{3,}", "\n\n")         // 3+ newlines → 2 newlines
                .trim();
    }

    // ─────────────────────────────────────────────
    // HELPER: Decode base64 URL-encoded string
    // Gmail stores email body as base64 URL-encoded
    // ─────────────────────────────────────────────
    private String decodeBase64(String encoded) {
        byte[] decodedBytes = Base64.getUrlDecoder().decode(encoded);
        return new String(decodedBytes);
    }
}