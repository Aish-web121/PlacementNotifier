# PlacementNotifier

A Spring Boot backend that monitors a Gmail inbox for placement/recruitment emails and delivers real-time job alerts to a Telegram group — with zero duplicate notifications.

Built for students at Thapar Institute of Engineering & Technology.

---

## What It Does

- Polls Gmail every 3 minutes for new emails from Recruitsage
- Parses job title, company, and role from each email
- Sends a formatted notification to a Telegram group
- Tracks processed emails by `message_id` to prevent duplicates
- Automatically deletes records older than 8 days to keep the database clean

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2.5 (Java 17) |
| Database | PostgreSQL (Supabase) |
| Email | Gmail API (OAuth 2.0) |
| Notifications | Telegram Bot API |
| Scheduling | Spring `@Scheduled` |
| Hosting | Azure App Service B1 |

---

## Architecture

```
Gmail Inbox
    ↓  (every 3 minutes)
EmailPollingScheduler
    ↓
GmailService  →  fetches emails via Gmail API
    ↓
NotificationOrchestrator
    ↓
EmailParserService  →  extracts company, role, type
    ↓
ProcessedEmailRepository  →  checks for duplicates via message_id
    ↓
TelegramService  →  sends alert to Telegram group
    ↓
CleanupScheduler  →  deletes records older than 8 days (runs daily at 2 AM)
```

---

## Environment Variables

All secrets are managed via environment variables. Never commit real values.

| Variable | Description |
|----------|-------------|
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | Database username |
| `DB_PASSWORD` | Database password |
| `GMAIL_CLIENT_ID` | Google OAuth Client ID |
| `GMAIL_CLIENT_SECRET` | Google OAuth Client Secret |
| `GMAIL_REFRESH_TOKEN` | Gmail OAuth Refresh Token |
| `TELEGRAM_BOT_TOKEN` | Telegram Bot API token |
| `TELEGRAM_CHAT_ID` | Telegram group chat ID (negative number) |

---

## Running Locally

**Prerequisites:**
- Java 17+
- PostgreSQL running locally
- Gmail OAuth credentials
- Telegram bot created via BotFather

**Steps:**

```bash
# Clone the repo
git clone https://github.com/Aish-web121/PlacementNotifier.git
cd PlacementNotifier

# Set environment variables (Windows PowerShell)
$env:DB_URL="jdbc:postgresql://localhost:5432/postgres"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your_password"
$env:GMAIL_CLIENT_ID="your_client_id"
$env:GMAIL_CLIENT_SECRET="your_client_secret"
$env:GMAIL_REFRESH_TOKEN="your_refresh_token"
$env:TELEGRAM_BOT_TOKEN="your_bot_token"
$env:TELEGRAM_CHAT_ID="your_group_chat_id"

# Build and run (skip tests)
./mvnw clean install -DskipTests
./mvnw spring-boot:run
```

---

## Deployment

- **Database:** Supabase (PostgreSQL free tier)
- **Backend:** Azure App Service B1 (always-on, supports scheduled tasks)
- **Secrets:** Configured as Azure Application Settings

---

## Key Features

**Duplicate Prevention**
Every processed email is stored with its unique Gmail `message_id`. Before sending any notification, the system checks if that ID already exists in the database.

**Auto Cleanup**
A scheduled job runs daily at 2 AM and deletes all records older than 8 days, keeping the database lightweight.

**Gmail Search**
Only fetches emails from Recruitsage in the last 24 hours (`newer_than:1d`), keeping each polling cycle fast.

---

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | App health status |
| `GET /actuator/info` | App info |
| `GET /status` | Custom status endpoint |

---

## Project Structure

```
src/main/java/PLACEMENT/COM/PLACEMENTNOTIFIER/
├── Config/
│   └── AppConfig.java               # Gmail API client setup
├── Controller/
│   └── StatusController.java        # Health/status endpoints
├── DTO/
│   └── ParsedEmailDto.java          # Email parsing result
├── Entity/
│   └── ProcessedEmail.java          # DB entity
├── Repository/
│   └── ProcessedEmailRepository.java
├── Scheduler/
│   ├── EmailPollingScheduler.java   # Triggers every 3 minutes
│   └── CleanupScheduler.java        # Triggers daily at 2 AM
├── Service/
│   ├── GmailService.java            # Gmail API integration
│   ├── EmailParserService.java      # Parses job details from email
│   ├── NotificationOrchestrator.java # Coordinates the pipeline
│   ├── TelegramService.java         # Sends Telegram messages
│   └── CleanupService.java          # Deletes old records
└── PlacementnotifierApplication.java
```

---

## Resume Line

> Built and deployed a placement notifier used by students from Thapar Institute, delivering real-time job alerts via Telegram with Gmail API integration and zero duplicate notifications.
