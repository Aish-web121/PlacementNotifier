package PLACEMENT.COM.PLACEMENTNOTIFIER.Entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// @Entity tells Hibernate: "this class is a database table"
@Entity

// @Table defines the actual table name in PostgreSQL
// The indexes make lookups faster:
//   - idx_message_id: used every 3 min to check "have I seen this email?"
//   - idx_processed_at: used for fetching recent emails
@Table(
        name = "processed_emails",
        indexes = {
                @Index(name = "idx_message_id", columnList = "message_id", unique = true),
                @Index(name = "idx_processed_at", columnList = "processed_at")
        }
)

// Lombok annotations — these GENERATE code at compile time:
// @Data        → generates all getters, setters, toString, equals, hashCode
// @Builder     → lets you do: ProcessedEmail.builder().messageId("x").build()
// @NoArgsConstructor → generates: new ProcessedEmail()
// @AllArgsConstructor → generates constructor with all fields
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEmail {

    // @Id = this is the primary key
    // @GeneratedValue = PostgreSQL auto-increments this (1, 2, 3, 4...)
    // You never set this manually
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The Gmail message ID — looks like "18f3a2b1c9d4e5f6"
    // unique = true → database REJECTS duplicates at the DB level
    // This is your deduplication guarantee
    @Column(name = "message_id", nullable = false, unique = true, length = 100)
    private String messageId;

    // Email subject line
    // e.g. "Exciting SDE Internship at Google!"
    @Column(name = "subject", length = 500)
    private String subject;

    // Who sent the email
    // e.g. "careers@google.com" or "HR Team <hr@infosys.com>"
    @Column(name = "sender", length = 300)
    private String sender;

    // Company name we extracted
    // e.g. "Google", "Infosys", "Unknown"
    @Column(name = "company", length = 200)
    private String company;

    // Job role we extracted
    // e.g. "SDE Intern", "Software Engineer", "Not specified"
    @Column(name = "role", length = 200)
    private String role;

    // Application deadline if found in email
    // e.g. "15 June 2025" — can be null if not mentioned
    @Column(name = "deadline", length = 100)
    private String deadline;

    // First 300 characters of the email body
    // Shown in Telegram notification as a preview
    @Column(name = "snippet", length = 1000)
    private String snippet;

    // Did we successfully send the Telegram notification?
    // true = sent OK
    // false = Telegram failed (but we still saved the row to avoid reprocessing)
    @Column(name = "notification_sent", nullable = false)
    @Builder.Default  // needed with @Builder — sets default value
    private boolean notificationSent = false;

    // @CreationTimestamp = Hibernate automatically sets this to "right now"
    // when the row is first inserted. You never set this manually.
    // updatable = false = once set, never changes
    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private LocalDateTime processedAt;
}