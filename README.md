# 🏆 Quiz Leaderboard System — Bajaj Finserv Health Qualifier

> A production-grade Spring Boot engine that polls an external validator API, deduplicates event data with surgical precision, aggregates participant scores, and submits an accurate leaderboard — all orchestrated via a drift-free scheduled executor with built-in circuit breaker resilience.

---

## 📌 Problem Statement

The external validator simulates a quiz show where multiple participants receive scores across rounds. Due to system behavior, **the same event data may appear across multiple polls**. The challenge is to:

1. Poll the validator API **10 times** (poll indexes `0` to `9`)
2. Maintain a strict **5-second delay** between each request
3. **Deduplicate** events using the composite key `roundId + participant`
4. **Aggregate** total scores per participant
5. Generate a **leaderboard sorted by totalScore (descending)**
6. **Submit once** via `POST /quiz/submit`

---

## 🛠 Tech Stack

| Layer         | Technology                                    |
|---------------|-----------------------------------------------|
| Language      | Java 17+                                      |
| Framework     | Spring Boot 3.2.4                             |
| HTTP Client   | Java 11 `java.net.http.HttpClient` (zero deps)|
| JSON          | Jackson `ObjectMapper`                        |
| Build Tool    | Maven                                         |
| Concurrency   | `ScheduledExecutorService`, `AtomicInteger`   |
| Thread Safety | `ConcurrentHashMap.newKeySet()`               |
| Logging       | SLF4J + Logback                               |

---

## 📂 Project Structure

```text
src/main/java/com/vidal/quiz/
├── QuizApplication.java              # Application entry point + RestTemplate bean
├── api/
│   └── QuizApiClient.java            # HTTP client with exponential backoff retries
├── config/
│   └── AppConfig.java                # Centralized configuration (URL, delays, retries)
├── controller/
│   └── QuizController.java           # REST endpoint to trigger the orchestration
├── model/
│   ├── Event.java                    # { roundId, participant, score }
│   ├── QuizMessageResponse.java      # GET /quiz/messages response DTO
│   ├── LeaderboardEntry.java         # { participant, totalScore } (rank is @JsonIgnore)
│   ├── SubmitRequest.java            # POST /quiz/submit request body
│   └── SubmitResponse.java           # POST /quiz/submit response body
└── service/
    ├── DeduplicationEngine.java      # Thread-safe event dedup using ConcurrentHashMap
    ├── LeaderboardBuilder.java       # Score aggregation + sorting + checksum audit
    └── PollOrchestrator.java         # ScheduledExecutorService + circuit breaker
```

---

## 🧠 Design Decisions

### 1. `ScheduledExecutorService` over `Thread.sleep`
A naive `for` loop with `Thread.sleep(5000)` suffers from **timing drift** — if a poll takes 1 second to process, the actual gap between requests becomes 6 seconds, not 5. We use `scheduleWithFixedDelay()` which guarantees a precise 5-second gap between the **end** of one task and the **start** of the next.

### 2. Deduplication Strategy
The composite key `roundId + "::" + participant` is stored in a `ConcurrentHashMap.newKeySet()`. This guarantees:
- **Correctness**: Only the first occurrence of each `(roundId, participant)` pair is counted
- **Thread Safety**: Safe for future async/parallel extensions
- **O(1) lookups**: HashSet-backed constant time duplicate detection

### 3. Exponential Backoff Retries
Each individual `GET` poll call is wrapped with up to 3 retries using exponential backoff (`1s → 2s → 4s`). This handles transient network failures without losing data.

### 4. Circuit Breaker
If **3 consecutive polls fail completely** (even after retries), the orchestrator aborts the entire process immediately. This prevents submitting corrupted or incomplete data to the validator.

### 5. Defensive Event Validation
Before processing, every event is validated:
- `participant` must not be `null`
- `roundId` must not be `null`
- `score` must not be negative

Invalid events are silently dropped with a warning log.

### 6. Pre-Submit Checksum
Before calling `POST /quiz/submit`, the system computes and logs the total score across all participants as `[PRE-SUBMIT CHECKSUM]`. This self-validation allows you to verify correctness before the API responds.

---

## 🚀 How to Run

### Prerequisites
- JDK 17 or higher
- Maven 3.6+

### 1. Clone & Build
```bash
git clone https://github.com/your-username/quiz-leaderboard-system.git
cd quiz-leaderboard-system
mvn clean install
```

### 2. Start the Server
```bash
mvn spring-boot:run
```
The server starts on `http://localhost:8080`.

### 3. Trigger the Orchestration
Open a new terminal and run:

```bash
# Linux / macOS / Git Bash
curl -X POST "http://localhost:8080/api/quiz/process?regNo=YOUR_REG_NO"

# Windows PowerShell (use curl.exe to avoid alias conflict)
curl.exe -X POST "http://localhost:8080/api/quiz/process?regNo=YOUR_REG_NO"
```

If no `regNo` is provided, it defaults to the value in `application.properties`.

---

## 📊 Sample Run — Real Execution Logs

Below is a real execution trace captured from a live run against the validator API:

```text
INFO  QuizController    : Received request to process quiz for regNo: RA2311050010029
INFO  PollOrchestrator  : Starting orchestrated polling for registration number: RA2311050010029

INFO  PollOrchestrator  : --- Executing Poll 0/9 ---
INFO  QuizApiClient     : [HTTP GET] Polling URL: .../quiz/messages?regNo=RA2311050010029&poll=0
INFO  DeduplicationEngine : Poll 0: Received 3 events, 3 unique after deduplication.

INFO  PollOrchestrator  : --- Executing Poll 1/9 ---
INFO  QuizApiClient     : [HTTP GET] Polling URL: .../quiz/messages?regNo=RA2311050010029&poll=1
DEBUG DeduplicationEngine : Duplicate event ignored: R2::Hannah
INFO  DeduplicationEngine : Poll 1: Received 2 events, 1 unique after deduplication.

  ... (polls 2-7 with similar dedup behavior) ...

INFO  PollOrchestrator  : --- Executing Poll 8/9 ---
DEBUG DeduplicationEngine : Duplicate event ignored: R1::Ivan
DEBUG DeduplicationEngine : Duplicate event ignored: R4::Hannah
INFO  DeduplicationEngine : Poll 8: Received 2 events, 0 unique after deduplication.

INFO  PollOrchestrator  : --- Executing Poll 9/9 ---
DEBUG DeduplicationEngine : Duplicate event ignored: R3::George
INFO  DeduplicationEngine : Poll 9: Received 1 events, 0 unique after deduplication.

INFO  PollOrchestrator  : All polls completed. Building final leaderboard...
INFO  LeaderboardBuilder: [PRE-SUBMIT CHECKSUM]: 2290
INFO  LeaderboardBuilder: Current Leaderboard State:
      [George=795, Hannah=750, Ivan=745]

INFO  QuizApiClient     : [HTTP POST] Submit URL: .../quiz/submit
INFO  QuizApiClient     : [HTTP POST] Submit Payload:
      {"regNo":"RA2311050010029","leaderboard":[
        {"participant":"George","totalScore":795},
        {"participant":"Hannah","totalScore":750},
        {"participant":"Ivan","totalScore":745}
      ]}

INFO  PollOrchestrator  : Final Submission Response:
      SubmitResponse(submittedTotal=2290, expectedTotal=2290, correct=true)
```

---

## 🛡 Edge Cases Handled

| Edge Case | How It's Handled |
|-----------|-----------------|
| Duplicate events across polls | Dedup via `roundId::participant` composite key |
| API returns HTTP 503 / timeout | Exponential backoff retry (up to 3 attempts) |
| 3+ consecutive poll failures | Circuit breaker aborts to prevent bad submission |
| Negative scores in events | Dropped during defensive validation |
| Null participant or roundId | Dropped during defensive validation |
| Timing drift between polls | `scheduleWithFixedDelay` ensures precise gaps |
| Extra fields in submit JSON | `rank` field is `@JsonIgnore` — invisible to API |
| Missing regNo parameter | Falls back to configured default |

---

## 🔧 Configuration

All settings are centralized in `application.properties`:

```properties
app.quiz.api-base-url=https://devapigw.vidalhealthtpa.com/srm-quiz-task
app.quiz.default-reg-no=RA2311050010029
app.quiz.total-polls=10
app.quiz.poll-delay-ms=5000
app.quiz.max-retries=3
```

---

## 📐 Workflow Diagram

```text
┌──────────────┐
│  POST /api/  │
│ quiz/process │
└──────┬───────┘
       │
       ▼
┌──────────────────┐
│ PollOrchestrator  │──── scheduleWithFixedDelay(5s)
│ (poll 0 → 9)     │
└──────┬───────────┘
       │ for each poll
       ▼
┌──────────────────┐     ┌───────────────────┐
│  QuizApiClient   │────▶│  GET /quiz/msgs   │
│  (retry x3)      │     │  (exponential     │
│                  │◀────│   backoff)        │
└──────┬───────────┘     └───────────────────┘
       │ events[]
       ▼
┌──────────────────┐
│ DeduplicationEng │──── ConcurrentHashMap<roundId::participant>
│ (filter dupes)   │
└──────┬───────────┘
       │ unique events
       ▼
┌──────────────────┐
│ LeaderboardBuild │──── ConcurrentHashMap<participant, score>
│ (aggregate)      │
└──────┬───────────┘
       │ after poll 9
       ▼
┌──────────────────┐     ┌───────────────────┐
│ Sort descending  │────▶│ POST /quiz/submit │
│ + checksum audit │     │ (single fire)     │
└──────────────────┘     └───────────────────┘
```

---

*Built with clean architecture, defensive engineering, and a deep respect for the edge case.* 🚀
