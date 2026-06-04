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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class GmailService {

    private final Gmail gmailService;

    // ─────────────────────────────────────────────
    // ADD ALL YOUR EMAIL SENDERS HERE
    // To add new sender: just add one line
    // e.g. "internshala.com"
    // ─────────────────────────────────────────────
    private static final List<String> SENDERS = List.of(
            "placements@thapar.edu",
            "recruitsage"
    );

    private static final int MAX_RESULTS = 10;

    // ─────────────────────────────────────────────
    // MAIN METHOD: called by scheduler every 3 min
    // Fetches from ALL senders separately
    // Combines results
    // Removes duplicates using messageId
    // ─────────────────────────────────────────────
    public List<Message> fetchPlacementEmails() {
        List<Message> allEmails = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (String sender : SENDERS) {
            try {
                log.info("Fetching emails from: {}", sender);

                String query = "from:" + sender + " newer_than:2d";

                ListMessagesResponse response = gmailService
                        .users()
                        .messages()
                        .list("me")
                        .setQ(query)
                        .setMaxResults((long) MAX_RESULTS)
                        .execute();

                if (response.getMessages() == null
                        || response.getMessages().isEmpty()) {
                    log.info("No new emails from: {}", sender);
                    continue;
                }

                log.info("Found {} emails from: {}",
                        response.getMessages().size(), sender);

                for (Message msg : response.getMessages()) {

                    // skip duplicates
                    if (seenIds.contains(msg.getId())) {
                        continue;
                    }

                    try {
                        Message fullMessage = gmailService
                                .users()
                                .messages()
                                .get("me", msg.getId())
                                .setFormat("FULL")
                                .execute();

                        allEmails.add(fullMessage);
                        seenIds.add(msg.getId());

                    } catch (IOException e) {
                        log.warn("Failed to fetch message {}: {}",
                                msg.getId(), e.getMessage());
                    }
                }

            } catch (IOException e) {
                // one sender failing doesn't stop others
                log.error("Failed to fetch from {}: {}",
                        sender, e.getMessage());
            }
        }

        log.info("Total placement emails fetched: {}",
                allEmails.size());
        return allEmails;
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
        if (message.getPayload() == null
                || message.getPayload().getHeaders() == null) {
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
    // Structure preserved so parser can find
    // company and role correctly
    // ─────────────────────────────────────────────
    public String getBody(Message message) {
        try {
            MessagePart payload = message.getPayload();
            if (payload == null) return "";

            // Case 1: Simple plain text email
            // body is directly in payload, no parts
            if (payload.getBody() != null
                    && payload.getBody().getData() != null) {
                return decodeBase64(payload.getBody().getData());
            }

            // Case 2: Multipart email
            if (payload.getParts() != null) {

                // First choice: plain text version
                for (MessagePart part : payload.getParts()) {
                    if ("text/plain".equals(part.getMimeType())
                            && part.getBody() != null
                            && part.getBody().getData() != null) {
                        return decodeBase64(part.getBody().getData());
                    }
                }

                // Second choice: HTML version
                for (MessagePart part : payload.getParts()) {
                    if ("text/html".equals(part.getMimeType())
                            && part.getBody() != null
                            && part.getBody().getData() != null) {
                        String html = decodeBase64(
                                part.getBody().getData());
                        return htmlToText(html);
                    }
                }

                // Case 3: nested multipart
                for (MessagePart part : payload.getParts()) {
                    if (part.getMimeType() != null
                            && part.getMimeType().startsWith("multipart")
                            && part.getParts() != null) {

                        for (MessagePart nested : part.getParts()) {
                            if ("text/plain".equals(nested.getMimeType())
                                    && nested.getBody() != null
                                    && nested.getBody().getData() != null) {
                                return decodeBase64(
                                        nested.getBody().getData());
                            }
                        }
                        for (MessagePart nested : part.getParts()) {
                            if ("text/html".equals(nested.getMimeType())
                                    && nested.getBody() != null
                                    && nested.getBody().getData() != null) {
                                return htmlToText(
                                        decodeBase64(
                                                nested.getBody().getData()));
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Could not extract email body: {}",
                    e.getMessage());
        }

        return "";
    }

    // ─────────────────────────────────────────────
    // HELPER: Convert HTML to structured plain text
    // Preserves structure by converting block tags
    // to newlines BEFORE stripping all tags
    // ─────────────────────────────────────────────
    private String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";

        return html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)<p[^>]*>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)<div[^>]*>", "\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)<tr[^>]*>", "\n")
                .replaceAll("(?i)</tr>", "\n")
                .replaceAll("(?i)<td[^>]*>", " ")
                .replaceAll("(?i)<li[^>]*>", "\n- ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n[ \\t]+", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    // ─────────────────────────────────────────────
    // HELPER: Decode base64 URL-encoded string
    // Gmail stores email body as base64 encoded
    // ─────────────────────────────────────────────
    private String decodeBase64(String encoded) {
        byte[] decodedBytes = Base64.getUrlDecoder().decode(encoded);
        return new String(decodedBytes);
    }
}
