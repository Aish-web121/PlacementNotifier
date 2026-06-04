package PLACEMENT.COM.PLACEMENTNOTIFIER.Service;

import PLACEMENT.COM.PLACEMENTNOTIFIER.DTO.ParsedEmailDto;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Entity.ProcessedEmail;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Repository.ProcessedEmailRepository;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Service.EmailParserService;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Service.GmailService;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Service.TelegramService;
import com.google.api.services.gmail.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationOrchestrator {

    // All 4 dependencies injected automatically by Spring
    // You never write "new GmailService()" etc.
    private final GmailService gmailService;
    private final EmailParserService emailParserService;
    private final TelegramService telegramService;
    private final ProcessedEmailRepository repository;

    // ─────────────────────────────────────────────
    // MAIN METHOD
    // Called by scheduler every 3 minutes
    //
    // @Transactional means:
    // if ANYTHING goes wrong while saving to DB,
    // the entire save is rolled back cleanly
    // no half-saved data ever
    // ─────────────────────────────────────────────
    @Transactional
    public void processNewEmails() {

        log.info("=== Starting email processing cycle ===");

        // ─────────────────────────
        // STEP 1: Fetch from Gmail
        // ─────────────────────────
        List<Message> emails;

        try {
            emails = gmailService.fetchPlacementEmails();
        } catch (Exception e) {
            // Gmail API failed (network issue, token expired etc.)
            // Log the error and STOP this cycle
            // Scheduler will try again in 3 minutes
            log.error("Gmail fetch failed: {}. Will retry next cycle.", e.getMessage());
            return;
        }

        // No emails found — perfectly normal
        // happens most of the time
        if (emails.isEmpty()) {
            log.info("No new placement emails this cycle.");
            return;
        }

        log.info("Processing {} emails this cycle.", emails.size());

        // Counters for logging at the end
        int processed = 0;
        int skipped   = 0;
        int failed    = 0;

        // ─────────────────────────
        // STEP 2: Process each email
        // ─────────────────────────
        for (Message message : emails) {
            String messageId = message.getId();

            // ── DEDUPLICATION CHECK ──
            // Ask database: "have I seen this email before?"
            // This runs on every email every 3 minutes
            // The idx_message_id index makes this instant
            if (repository.existsByMessageId(messageId)) {
                log.debug("Skipping already processed email: {}", messageId);
                skipped++;
                continue; // skip to next email
            }

            // ── This is a NEW email — process it ──
            try {

                // STEP 2a: Parse the email
                // Extract company, role, deadline, snippet
                ParsedEmailDto parsed = emailParserService.parse(message);

                log.info("New email found → Company: {} | Role: {} | Subject: {}",
                        parsed.getCompany(),
                        parsed.getRole(),
                        parsed.getSubject());

                // STEP 2b: Send Telegram notification
                // Returns true if sent, false if all retries failed
                boolean notificationSent = telegramService
                        .sendPlacementNotification(parsed);

                // STEP 2c: Save to database
                // We save REGARDLESS of whether Telegram succeeded
                // Reason: if we don't save, next cycle will try again
                // and if Telegram is back up, you get a duplicate notification
                // Better to save and mark notification_sent=false
                saveToDatabase(parsed, notificationSent);

                if (notificationSent) {
                    log.info("✅ Email processed and notification sent.");
                    processed++;
                } else {
                    log.warn("⚠️ Email saved but Telegram notification failed.");
                    failed++;
                }

            } catch (Exception e) {
                // Something went wrong parsing THIS specific email
                // Log it, save a stub so we don't retry it forever
                // Then CONTINUE to the next email
                // One bad email should never stop the others
                log.error("Failed to process email {}: {}", messageId, e.getMessage());
                saveErrorStub(messageId, e.getMessage());
                failed++;
            }
        }

        // ─────────────────────────
        // STEP 3: Log cycle summary
        // ─────────────────────────
        log.info("=== Cycle complete | Processed: {} | Skipped: {} | Failed: {} ===",
                processed, skipped, failed);
    }

    // ─────────────────────────────────────────────
    // Save successfully parsed email to database
    // ─────────────────────────────────────────────
    private void saveToDatabase(ParsedEmailDto dto, boolean notificationSent) {
        try {
            ProcessedEmail entity = ProcessedEmail.builder()
                    .messageId(dto.getMessageId())
                    .subject(dto.getSubject())
                    .sender(dto.getSender())
                    .company(dto.getCompany())
                    .role(dto.getRole())
                    .deadline(dto.getDeadline())
                    .snippet(dto.getSnippet())
                    .notificationSent(notificationSent)
                    .build();

            repository.save(entity);
            log.debug("Saved to database: {}", dto.getMessageId());

        } catch (Exception e) {
            log.error("Failed to save email {} to database: {}",
                    dto.getMessageId(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Save a stub row for emails that failed to parse
    //
    // Why? If we don't save anything, the scheduler
    // will try to process this broken email EVERY 3 MINUTES
    // forever. That wastes API quota and fills logs with errors.
    //
    // Instead we save a stub with just the messageId
    // so existsByMessageId() returns true next time
    // and the broken email gets skipped cleanly
    // ─────────────────────────────────────────────
    private void saveErrorStub(String messageId, String errorMessage) {
        try {
            // Only save if not already in database
            if (!repository.existsByMessageId(messageId)) {
                ProcessedEmail stub = ProcessedEmail.builder()
                        .messageId(messageId)
                        .subject("PARSE ERROR")
                        .company("Unknown")
                        .role("Unknown")
                        .snippet(errorMessage != null
                                ? errorMessage.substring(0, Math.min(errorMessage.length(), 200))
                                : "Unknown error")
                        .notificationSent(false)
                        .build();

                repository.save(stub);
                log.debug("Saved error stub for: {}", messageId);
            }
        } catch (Exception e) {
            log.error("Could not save error stub for {}: {}", messageId, e.getMessage());
        }
    }
}