package PLACEMENT.COM.PLACEMENTNOTIFIER.Repository;

import PLACEMENT.COM.PLACEMENTNOTIFIER.Entity.ProcessedEmail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// @Repository tells Spring: "this is a database access class"
// Spring will create the actual implementation automatically at startup.
// You never write: new ProcessedEmailRepository() — Spring does it.
@Repository

// JpaRepository<ProcessedEmail, Long> means:
//   - ProcessedEmail = which table/entity we're working with
//   - Long = the data type of the primary key (our "id" field)
// By extending this, you get 20+ free methods instantly:
//   save(), findById(), findAll(), deleteById(), count() etc.
public interface ProcessedEmailRepository extends JpaRepository<ProcessedEmail, Long> {

    // ✅ METHOD 1: Deduplication check
    // Spring reads "existsByMessageId" and generates this SQL:
    // SELECT COUNT(*) > 0 FROM processed_emails WHERE message_id = ?
    //
    // Called every 3 minutes for each email we fetch.
    // Returns true = already processed, skip it
    // Returns false = new email, process it
    boolean existsByMessageId(String messageId);

    // ✅ METHOD 2: Count emails processed in last 24 hours
    // We can't auto-generate this one because it uses a calculation (>=)
    // so we write the query manually using @Query
    // "e" is just an alias for ProcessedEmail (like SQL's table alias)
    // :since is the parameter we pass in (e.g. yesterday's datetime)
    @Query("SELECT COUNT(e) FROM ProcessedEmail e WHERE e.processedAt >= :since")
    long countProcessedSince(LocalDateTime since);

    // ✅ METHOD 3: Fetch 20 most recent processed emails
    // Spring reads this method name and generates:
    // SELECT * FROM processed_emails ORDER BY processed_at DESC LIMIT 20
    //
    // Used by your /api/emails/recent endpoint so you can
    // check what the app has been doing
    List<ProcessedEmail> findTop20ByOrderByProcessedAtDesc();

    //cleanUp when record is older than 8 days
    @Modifying
    @Transactional
    @Query("DELETE FROM ProcessedEmail e WHERE e.processedAt <= :cutoff")
    void deleteOlderThan(LocalDateTime cutoff);
}