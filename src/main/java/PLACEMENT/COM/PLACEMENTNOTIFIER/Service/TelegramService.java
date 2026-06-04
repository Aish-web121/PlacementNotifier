package PLACEMENT.COM.PLACEMENTNOTIFIER.Service;

import PLACEMENT.COM.PLACEMENTNOTIFIER.DTO.ParsedEmailDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramService {

    // WebClient bean from AppConfig — injected automatically by Spring
    private final WebClient webClient;

    // Reads TELEGRAM_BOT_TOKEN from environment variable
    @Value("${telegram.bot-token}")
    private String botToken;

    // Reads TELEGRAM_CHAT_ID from environment variable
    @Value("${telegram.chat-id}")
    private String chatId;

    // Telegram's API URL pattern
    // %s gets replaced with your botToken at runtime
    // e.g. "https://api.telegram.org/bot123456:ABC/sendMessage"
    private static final String TELEGRAM_URL =
            "https://api.telegram.org/bot%s/sendMessage";

    // ─────────────────────────────────────────────
    // MAIN METHOD: Send placement notification
    // Returns true if sent successfully
    // Returns false if failed (so orchestrator knows)
    // ─────────────────────────────────────────────
    public boolean sendPlacementNotification(ParsedEmailDto email) {
        String message = buildMessage(email);
        return sendMessage(message);
    }

    // ─────────────────────────────────────────────
    // Send a raw text message to Telegram
    // Has retry logic — tries 3 times before giving up
    // ─────────────────────────────────────────────
    private boolean sendMessage(String text) {

        // Build the URL with your bot token inserted
        String url = String.format(TELEGRAM_URL, botToken);

        // Build the request body
        // Telegram API expects these exact field names
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("chat_id", chatId);         // who to send to
        requestBody.put("text", text);              // the message content
        requestBody.put("parse_mode", "Markdown");  // enable bold, italic formatting

        // Try up to 3 times
        // If Telegram is temporarily down, we retry instead of failing immediately
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            attempt++;
            try {
                log.info("Sending Telegram notification (attempt {}/{})", attempt, maxRetries);

                webClient
                        .post()          // HTTP POST request
                        .uri(url)        // to Telegram's API
                        .bodyValue(requestBody)  // with our message data
                        .retrieve()      // execute the request
                        .bodyToMono(String.class)  // read response as String
                        .block(Duration.ofSeconds(10)); // wait max 10 seconds

                // If we reach here — success!
                log.info("Telegram notification sent successfully.");
                return true;

            } catch (WebClientResponseException e) {
                // Telegram returned an error response (4xx or 5xx)
                log.error("Telegram API error on attempt {}: {} - {}",
                        attempt, e.getStatusCode(), e.getResponseBodyAsString());

                // 400 Bad Request = wrong chat_id or message format
                // Retrying won't help — break immediately
                if (e.getStatusCode().value() == 400) {
                    log.error("Bad request — check TELEGRAM_CHAT_ID and message format.");
                    return false;
                }

            } catch (Exception e) {
                // Network error, timeout etc.
                log.error("Telegram send failed on attempt {}: {}", attempt, e.getMessage());
            }

            // Wait 2 seconds before retrying
            // Don't hammer Telegram's API if it's struggling
            if (attempt < maxRetries) {
                try {
                    log.info("Waiting 2 seconds before retry...");
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        // All 3 attempts failed
        log.error("All {} Telegram attempts failed.", maxRetries);
        return false;
    }

    // ─────────────────────────────────────────────
    // BUILD THE MESSAGE
    // This is what you actually see on Telegram
    // Using your RecruitSage email as example:
    //
    // 📢 New Placement Opportunity!
    //
    // 🏢 Company: Honeywell
    // 💼 Role: Data Scientist
    // 📧 Subject: New Job Opportunity!
    // 👤 From: RecruitSage
    //
    // 📝 A new job opportunity has been posted...
    //
    // 🕐 Check your Gmail for full details
    // ─────────────────────────────────────────────
    private String buildMessage(ParsedEmailDto email) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("📢 *New Placement Opportunity!*\n\n");

        // Company — always show
        sb.append("🏢 *Company:* ")
                .append(escape(email.getCompany()))
                .append("\n");

        // Role — always show
        sb.append("💼 *Role:* ")
                .append(escape(email.getRole()))
                .append("\n");

        // Deadline — only show if we found one
        // RecruitSage emails don't have deadlines so this will be skipped
        if (email.getDeadline() != null && !email.getDeadline().isEmpty()) {
            sb.append("📅 *Deadline:* ")
                    .append(escape(email.getDeadline()))
                    .append("\n");
        }

        // Subject line
        sb.append("📧 *Subject:* ")
                .append(escape(email.getSubject()))
                .append("\n");

        // Sender name
        sb.append("👤 *From:* ")
                .append(escape(email.getSender()))
                .append("\n");

        // Email preview — only if available
        if (email.getSnippet() != null && !email.getSnippet().isEmpty()) {
            sb.append("\n📝 ")
                    .append(escape(email.getSnippet()))
                    .append("\n");
        }

        // Footer
        sb.append("\n🕐 _Check your Gmail for full details_");

        return sb.toString();
    }
    // ─────────────────────────────────────────────
// Send a test message to verify bot is working
// Called by StatusController /api/test-telegram
// ─────────────────────────────────────────────
    public boolean sendTestMessage() {
        String message =
                "✅ *Placement Notifier is Live!*\n\n" +
                        "Your bot is connected and working.\n" +
                        "You will receive placement notifications here.\n\n" +
                        "🚀 _System is running and monitoring your Gmail_";
        return sendMessage(message);
    }

    // ─────────────────────────────────────────────
    // ESCAPE special Markdown characters
    //
    // Telegram Markdown treats these as formatting:
    // * = bold, _ = italic, ` = code, [ = link
    //
    // If your company name is "L&T_Technology"
    // the _ would break the formatting
    // So we escape it: "L&T\_Technology"
    // ─────────────────────────────────────────────
    private String escape(String text) {
        if (text == null) return "N/A";
        return text
                .replace("_", "\\_")   // italic marker
                .replace("*", "\\*")   // bold marker
                .replace("[", "\\[")   // link marker
                .replace("`", "\\`");  // code marker
    }
}