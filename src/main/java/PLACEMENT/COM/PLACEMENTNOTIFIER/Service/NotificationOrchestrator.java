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

    private final GmailService gmailService;
    private final EmailParserService emailParserService;
    private final TelegramService telegramService;
    private final ProcessedEmailRepository repository;

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
            log.error("Gmail fetch failed: {}. Will retry next cycle.", e.getMessage());
            return;
        }

        if (emails.isEmpty()) {
            log.info("No new placement emails this cycle.");
            return;
        }

        log.info("Processing {} emails this cycle.", emails.size());

        int processed = 0;
        int skipped   = 0;
        int failed    = 0;

        // ─────────────────────────
        // STEP 2: Process each email
        // ─────────────────────────
        for (Message message : emails) {
            String messageId = message.getId();

            // ── DEDUPLICATION CHECK ──
            if (repository.existsByMessageId(messageId)) {
                log.debug("Skipping already processed email: {}", messageId);
                skipped++;
                continue;
            }

            // ── This is a NEW email — process it ──
            try {

                // STEP 2a: Parse the email
                // Extract company, role, deadline, snippet
                ParsedEmailDto parsed = emailParserService.parse(message);

                // If Gemini marked email as not relevant, skip it cleanly
                if (parsed == null) {
                    log.info("Email not relevant, saving stub to skip next time.");
                    saveErrorStub(messageId, "Gemini marked as not relevant");
                    skipped++;
                    continue;
                }

                log.info("New email found → Company: {} | Role: {} | Subject: {}",
                        parsed.getCompany(),
                        parsed.getRole(),
                        parsed.getSubject());

                // STEP 2b: Send Telegram notification
                boolean notificationSent = telegramService
                        .sendPlacementNotification(parsed);

                // STEP 2c: Save to database
                saveToDatabase(parsed, notificationSent);

                if (notificationSent) {
                    log.info("✅ Email processed and notification sent.");
                    processed++;
                } else {
                    log.warn("⚠️ Email saved but Telegram notification failed.");
                    failed++;
                }

            } catch (Exception e) {
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
    // or were marked not relevant by Gemini
    // ─────────────────────────────────────────────
    private void saveErrorStub(String messageId, String errorMessage) {
        try {
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
