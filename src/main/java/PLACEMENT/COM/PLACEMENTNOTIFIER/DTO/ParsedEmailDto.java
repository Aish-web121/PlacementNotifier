package PLACEMENT.COM.PLACEMENTNOTIFIER.DTO;

import lombok.Builder;
import lombok.Data;

// No @Entity here — this is NOT a database table
// Just a plain Java class that carries data

@Data    // generates getters, setters, toString
@Builder // lets you do: ParsedEmailDto.builder().company("Google").build()
public class ParsedEmailDto {

    // Gmail's unique ID for this email
    // e.g. "18f3a2b1c9d4e5f6"
    private String messageId;

    // The email subject line
    // e.g. "New Job Opportunity!"
    private String subject;

    // Who sent it — cleaned up version
    // e.g. "RecruitSage" (not "RecruitSage <noreply@recruitsage.com>")
    private String sender;

    // Company name we extracted
    // e.g. "Altair", "Google", "Unknown"
    private String company;

    // Job role we extracted
    // e.g. "Post Graduate Trainee", "SDE Intern"
    private String role;

    // Deadline if found — null if not mentioned in email
    // e.g. "15 June 2025" or null
    private String deadline;

    // Short preview of email body
    // e.g. "A new job opportunity has been posted..."
    private String snippet;
}