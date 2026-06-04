package PLACEMENT.COM.PLACEMENTNOTIFIER.Controller;

import PLACEMENT.COM.PLACEMENTNOTIFIER.Entity.ProcessedEmail;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Repository.ProcessedEmailRepository;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Service.NotificationOrchestrator;
import PLACEMENT.COM.PLACEMENTNOTIFIER.Service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// @RestController = this class handles HTTP requests
// Every method returns data (JSON) directly
// not a webpage — just raw data
@RestController

// @RequestMapping = all endpoints in this class
// start with /api
// e.g. /api/health, /api/trigger, /api/emails/recent
@RequestMapping("/api")

@RequiredArgsConstructor
@Slf4j
public class StatusController {

    private final ProcessedEmailRepository repository;
    private final NotificationOrchestrator orchestrator;
    private final TelegramService telegramService;

    // ─────────────────────────────────────────────
    // ENDPOINT 1: Health Check
    // GET http://localhost:8080/api/health
    //
    // Azure App Service pings this URL every few minutes
    // If it returns 200 OK → app is alive → Azure keeps it running
    // If it returns error → Azure restarts the app
    //
    // Also useful for YOU to check:
    // "how many emails has my app processed today?"
    // ─────────────────────────────────────────────
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {

        long totalEmails = repository.count();
        long last24Hours = repository.countProcessedSince(
                LocalDateTime.now().minusHours(24)
        );

        return ResponseEntity.ok(Map.of(
                "status",        "UP",
                "totalEmails",   totalEmails,
                "last24Hours",   last24Hours,
                "timestamp",     LocalDateTime.now().toString()
        ));
    }

    // ─────────────────────────────────────────────
    // ENDPOINT 2: See recent emails
    // GET http://localhost:8080/api/emails/recent
    //
    // Returns last 20 processed emails as JSON
    // Useful to check:
    // "did my app correctly extract company and role?"
    // ─────────────────────────────────────────────
    @GetMapping("/emails/recent")
    public ResponseEntity<List<ProcessedEmail>> recentEmails() {
        List<ProcessedEmail> emails =
                repository.findTop20ByOrderByProcessedAtDesc();
        return ResponseEntity.ok(emails);
    }

    // ─────────────────────────────────────────────
    // ENDPOINT 3: Manual Trigger
    // POST http://localhost:8080/api/trigger
    //
    // Manually runs one Gmail poll RIGHT NOW
    // without waiting for the 3-minute scheduler
    //
    // USE THIS FOR TESTING:
    // Send yourself a test placement email
    // then hit this endpoint
    // check if Telegram notification arrives
    // ─────────────────────────────────────────────
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> triggerManually() {
        log.info("Manual trigger called via API.");
        try {
            orchestrator.processNewEmails();
            return ResponseEntity.ok(Map.of(
                    "status",  "SUCCESS",
                    "message", "Gmail poll executed. Check Telegram!"
            ));
        } catch (Exception e) {
            log.error("Manual trigger failed: {}", e.getMessage());
            return ResponseEntity
                    .internalServerError()
                    .body(Map.of(
                            "status",  "FAILED",
                            "message", e.getMessage()
                    ));
        }
    }

    // ─────────────────────────────────────────────
    // ENDPOINT 4: Test Telegram
    // POST http://localhost:8080/api/test-telegram
    //
    // Sends a test message to your Telegram
    // USE THIS FIRST before testing the full app
    // Confirms your bot token and chat ID are correct
    // ─────────────────────────────────────────────
    @PostMapping("/test-telegram")
    public ResponseEntity<Map<String, String>> testTelegram() {
        log.info("Telegram test called via API.");

        boolean sent = telegramService.sendTestMessage();

        if (sent) {
            return ResponseEntity.ok(Map.of(
                    "status",  "SUCCESS",
                    "message", "Test message sent! Check your Telegram group."
            ));
        }

        return ResponseEntity
                .internalServerError()
                .body(Map.of(
                        "status",  "FAILED",
                        "message", "Check TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID"
                ));
    }
}