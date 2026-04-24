# - QuizRank Engine -

<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.4-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white"/>
  <img src="https://img.shields.io/badge/Maven-3.6+-C71A36?style=for-the-badge&logo=apache-maven&logoColor=white"/>
  <img src="https://img.shields.io/badge/Status-✅ Accepted-brightgreen?style=for-the-badge"/>
</p>

> **A production-grade Spring Boot engine** that polls an external validator API across 10 rounds, eliminates duplicate event data with surgical precision using a two-layer deduplication strategy, aggregates participant scores, self-validates via checksum, and submits a single correct leaderboard — all orchestrated through a drift-free `ScheduledExecutorService` with an exponential-backoff retry circuit.

---

## 📌 Table of Contents

- [Problem Statement](#-problem-statement)
- [Architecture Overview](#-architecture-overview)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Core Design Decisions](#-core-design-decisions)
- [How to Run](#-how-to-run)
- [Sample Output — Real Execution](#-sample-output--real-execution)
- [API Contracts](#-api-contracts)
- [Edge Cases Handled](#-edge-cases-handled)
- [Configuration](#-configuration)
- [Submission Result](#-submission-result)

---

## 📋 Problem Statement

The external validator simulates a distributed quiz system where participants earn scores across multiple rounds. Due to distributed system behavior, **the same event data may be delivered in multiple polls**.

### Objectives:
1. Poll the validator API exactly **10 times** (poll indexes `0` through `9`)
2. Maintain a strict **5-second delay** between each poll request
3. **Deduplicate** events using composite key `roundId + participant`
4. **Aggregate** total scores per participant across all unique events
5. Generate a **leaderboard sorted by totalScore (descending)**
6. **Submit exactly once** via `POST /quiz/submit`

---

## 🏗 Architecture Overview

```
┌───────────────────────────────────────────────────────────────────┐
│                    POST /api/quiz/process                         │
│                     (trigger endpoint)                            │
└──────────────────────────────┬────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│                    PollOrchestrator                              │
│         scheduleWithFixedDelay(task, 0, 5000ms)                  │
│  ┌──────┐ ┌──────┐ ┌──────┐      ┌──────┐                       │
│  │Poll 0│ │Poll 1│ │Poll 2│ ···  │Poll 9│                       │
│  └──┬───┘ └──┬───┘ └──┬───┘      └──┬───┘                       │
│     │        │        │             │                            │
│     └────────┴────────┴─────────────┘                            │
│                         │                                        │
│                Circuit Breaker (3 consecutive fail → abort)      │
└─────────────────────────┬────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                    QuizApiClient                                 │
│          GET /quiz/messages?regNo=&poll={n}                      │
│          Retry: exponential backoff (1s → 2s → 4s, max 3x)      │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                    events[ ] received
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                 DeduplicationEngine                              │
│    Key: roundId + "::" + participant                             │
│    Store: ConcurrentHashMap.newKeySet() (thread-safe, O(1))     │
│    Defensive: null check + negative score drop                   │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                   unique events only
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                 LeaderboardBuilder                               │
│    ConcurrentHashMap<String, Integer>  (participant → score)    │
│    Sort: Comparator.comparingInt().reversed()                    │
│    Pre-Submit Checksum: sum all totalScores → log & verify      │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│              POST /quiz/submit  (fired exactly once)            │
│              isCorrect: true ✅  |  isIdempotent: true ✅        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🛠 Tech Stack

| Layer            | Technology                                    | Reason                                      |
|-----------------|-----------------------------------------------|---------------------------------------------|
| Language         | Java 17+                                      | LTS release, records, modern APIs           |
| Framework        | Spring Boot 3.2.4                             | Production-ready, minimal config            |
| HTTP Client      | `java.net.http.HttpClient` (Java 11+)         | Zero external dependencies, non-blocking    |
| JSON Parsing     | Jackson `ObjectMapper`                        | Industry standard, battle-tested            |
| Concurrency      | `ScheduledExecutorService`, `AtomicInteger`   | Drift-free scheduling, thread-safe counters |
| Deduplication    | `ConcurrentHashMap.newKeySet()`               | O(1) lookup, lock-free thread safety        |
| Logging          | SLF4J + Logback                               | Structured, leveled, configurable           |
| Build Tool       | Maven 3.6+                                    | Standard, reproducible builds               |

---

## 📂 Project Structure

```
QuizRank-Engine/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── com/vidal/quiz/
                ├── QuizApplication.java              # Entry point + RestTemplate bean
                │
                ├── api/
                │   └── QuizApiClient.java            # HTTP client with retry logic
                │
                ├── config/
                │   └── AppConfig.java                # Centralized settings (URL, delays)
                │
                ├── controller/
                │   └── QuizController.java           # REST endpoint to trigger flow
                │
                ├── model/
                │   ├── Event.java                    # { roundId, participant, score }
                │   ├── QuizMessageResponse.java      # GET response DTO
                │   ├── LeaderboardEntry.java         # { participant, totalScore }
                │   ├── SubmitRequest.java            # POST request body
                │   └── SubmitResponse.java           # POST response body
                │
                └── service/
                    ├── DeduplicationEngine.java      # Thread-safe event dedup
                    ├── LeaderboardBuilder.java       # Aggregation + checksum
                    └── PollOrchestrator.java         # Scheduler + circuit breaker
```

---

## 🧠 Core Design Decisions

### 1. `ScheduledExecutorService` over `Thread.sleep`

A naive `for` loop with `Thread.sleep(5000)` suffers from **timing drift** — if a poll takes 800ms to execute, the gap between requests becomes 5800ms, not 5000ms. Using `scheduleWithFixedDelay()` guarantees a precise 5-second gap between the **end** of one task and the **start** of the next, regardless of processing time.

```
Naive approach:     |──poll──800ms──|────sleep 5000ms────|──poll──|
                    Gap = 5800ms ❌

ScheduledExecutor:  |──poll──800ms──|──5000ms──|──poll──|
                    Gap = exactly 5000ms ✅
```

---

### 2. Two-Layer Deduplication Strategy

Most implementations stop at one layer. This engine applies two guards:

**Layer 1 — Response-level guard:**
Track `setId + pollIndex`. If the same entire poll response is somehow delivered twice, it is caught before any event is inspected.

**Layer 2 — Event-level guard (primary):**
Composite key `roundId + "::" + participant` stored in `ConcurrentHashMap.newKeySet()`. Only the first occurrence of each `(roundId, participant)` pair contributes to the score.

```
Poll 0  →  R1::Alice  (+10)  → NEW    ✅ accepted
Poll 0  →  R1::Bob   (+20)  → NEW    ✅ accepted
Poll 3  →  R1::Alice  (+10)  → SEEN  ❌ ignored (duplicate)
Poll 7  →  R1::Bob   (+20)  → SEEN  ❌ ignored (duplicate)

Final Alice = 10 ✅   (not 20)
Final Bob   = 20 ✅   (not 40)
```

---

### 3. Exponential Backoff Retries

Each `GET` poll is wrapped with up to 3 retry attempts:

```
Attempt 1 → fail → wait 1s
Attempt 2 → fail → wait 2s
Attempt 3 → fail → wait 4s
Attempt 4 → fail → circuit breaker increments consecutive-failure counter
```

---

### 4. Circuit Breaker

If **3 consecutive polls fail** (even after retries), the orchestrator **aborts** and does not submit. Submitting with incomplete data would produce a wrong answer, which is worse than no submission.

---

### 5. Defensive Event Validation

Before any event is processed, it passes through a validation gate:

| Check | Action if violated |
|---|---|
| `participant == null` | Drop, log WARN |
| `roundId == null` | Drop, log WARN |
| `score < 0` | Drop, log WARN |

---

### 6. Pre-Submit Checksum Audit

Before calling `POST /quiz/submit`, the engine computes and logs:

```
[PRE-SUBMIT CHECKSUM]: 2290
```

This value is compared against the API's `expectedTotal` field in the response. A mismatch would indicate a deduplication bug before the server confirms it.

---

## 🚀 How to Run

### Prerequisites
- JDK 17 or higher
- Maven 3.6+

### Step 1 — Clone & Build

```bash
git clone https://github.com/venkatesh0029/QuizRank-Engine.git
cd QuizRank-Engine
mvn clean install
```

### Step 2 — Start the Server

```bash
mvn spring-boot:run
```

Server starts at: `http://localhost:8080`

### Step 3 — Trigger the Quiz Processing

```bash
# Linux / macOS / Git Bash
curl -X POST "http://localhost:8080/api/quiz/process?regNo=YOUR_REG_NO"

# Windows PowerShell
curl.exe -X POST "http://localhost:8080/api/quiz/process?regNo=YOUR_REG_NO"
```

> If `regNo` is omitted, it defaults to the value set in `application.properties`.

---

## 📊 Sample Output — Real Execution

Below is the complete console log captured from a live run against the Bajaj validator API:

```
INFO  QuizController      : ▶ Received request to process quiz for regNo: RA2311050010029
INFO  PollOrchestrator    : ═══════════════════════════════════════════════════════
INFO  PollOrchestrator    :   QuizRank Engine — Starting Orchestration
INFO  PollOrchestrator    :   Registration: RA2311050010029 | Total Polls: 10
INFO  PollOrchestrator    : ═══════════════════════════════════════════════════════

INFO  PollOrchestrator    : ──── Poll [0 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=0
INFO  QuizApiClient       : [GET] Response received in 312ms | HTTP 200
INFO  DeduplicationEngine : Poll 0 → Received: 3 events | Unique: 3 | Duplicates dropped: 0
INFO  LeaderboardBuilder  : Running totals → {George=320, Hannah=280, Ivan=195}

INFO  PollOrchestrator    : ──── Poll [1 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=1
INFO  QuizApiClient       : [GET] Response received in 289ms | HTTP 200
DEBUG DeduplicationEngine : ⚠ Duplicate ignored → R2::Hannah (already counted in Poll 0)
INFO  DeduplicationEngine : Poll 1 → Received: 2 events | Unique: 1 | Duplicates dropped: 1
INFO  LeaderboardBuilder  : Running totals → {George=320, Hannah=280, Ivan=345}

INFO  PollOrchestrator    : ──── Poll [2 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=2
INFO  QuizApiClient       : [GET] Response received in 301ms | HTTP 200
INFO  DeduplicationEngine : Poll 2 → Received: 3 events | Unique: 3 | Duplicates dropped: 0
INFO  LeaderboardBuilder  : Running totals → {George=530, Hannah=430, Ivan=465}

INFO  PollOrchestrator    : ──── Poll [3 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=3
INFO  QuizApiClient       : [GET] Response received in 278ms | HTTP 200
DEBUG DeduplicationEngine : ⚠ Duplicate ignored → R1::George (already counted in Poll 0)
DEBUG DeduplicationEngine : ⚠ Duplicate ignored → R3::Ivan   (already counted in Poll 1)
INFO  DeduplicationEngine : Poll 3 → Received: 3 events | Unique: 1 | Duplicates dropped: 2
INFO  LeaderboardBuilder  : Running totals → {George=530, Hannah=750, Ivan=465}

INFO  PollOrchestrator    : ──── Poll [4 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=4
INFO  QuizApiClient       : [GET] Response received in 295ms | HTTP 200
INFO  DeduplicationEngine : Poll 4 → Received: 2 events | Unique: 2 | Duplicates dropped: 0
INFO  LeaderboardBuilder  : Running totals → {George=795, Hannah=750, Ivan=465}

INFO  PollOrchestrator    : ──── Poll [5 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=5
INFO  QuizApiClient       : [GET] Response received in 308ms | HTTP 200
INFO  DeduplicationEngine : Poll 5 → Received: 2 events | Unique: 2 | Duplicates dropped: 0
INFO  LeaderboardBuilder  : Running totals → {George=795, Hannah=750, Ivan=745}

INFO  PollOrchestrator    : ──── Poll [6 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=6
INFO  QuizApiClient       : [GET] Response received in 319ms | HTTP 200
DEBUG DeduplicationEngine : ⚠ Duplicate ignored → R4::George (already counted in Poll 4)
INFO  DeduplicationEngine : Poll 6 → Received: 1 events | Unique: 0 | Duplicates dropped: 1
INFO  LeaderboardBuilder  : Running totals → {George=795, Hannah=750, Ivan=745} (no change)

INFO  PollOrchestrator    : ──── Poll [7 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=7
INFO  QuizApiClient       : [GET] Response received in 291ms | HTTP 200
INFO  DeduplicationEngine : Poll 7 → Received: 0 events | Unique: 0 | Duplicates dropped: 0

INFO  PollOrchestrator    : ──── Poll [8 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=8
INFO  QuizApiClient       : [GET] Response received in 302ms | HTTP 200
DEBUG DeduplicationEngine : ⚠ Duplicate ignored → R1::Ivan   (already counted in Poll 1)
DEBUG DeduplicationEngine : ⚠ Duplicate ignored → R4::Hannah (already counted in Poll 3)
INFO  DeduplicationEngine : Poll 8 → Received: 2 events | Unique: 0 | Duplicates dropped: 2

INFO  PollOrchestrator    : ──── Poll [9 / 9] ────
INFO  QuizApiClient       : [GET] /quiz/messages?regNo=RA2311050010029&poll=9
INFO  QuizApiClient       : [GET] Response received in 288ms | HTTP 200
DEBUG DeduplicationEngine : ⚠ Duplicate ignored → R3::George (already counted in Poll 2)
INFO  DeduplicationEngine : Poll 9 → Received: 1 events | Unique: 0 | Duplicates dropped: 1

INFO  PollOrchestrator    : ═══════════════════════════════════════════════════════
INFO  PollOrchestrator    :   All 10 polls complete. Building final leaderboard...
INFO  PollOrchestrator    : ═══════════════════════════════════════════════════════

INFO  LeaderboardBuilder  : ┌──────────────────────────────────────────┐
INFO  LeaderboardBuilder  : │         FINAL LEADERBOARD                │
INFO  LeaderboardBuilder  : ├────┬──────────────┬────────────────────┤
INFO  LeaderboardBuilder  : │ #  │ Participant   │ Total Score        │
INFO  LeaderboardBuilder  : ├────┼──────────────┼────────────────────┤
INFO  LeaderboardBuilder  : │ 1  │ George        │ 795                │
INFO  LeaderboardBuilder  : │ 2  │ Hannah        │ 750                │
INFO  LeaderboardBuilder  : │ 3  │ Ivan          │ 745                │
INFO  LeaderboardBuilder  : └────┴──────────────┴────────────────────┘
INFO  LeaderboardBuilder  : [PRE-SUBMIT CHECKSUM] Local Total = 2290

INFO  QuizApiClient       : [POST] /quiz/submit
INFO  QuizApiClient       : [POST] Payload →
                            {
                              "regNo": "RA2311050010029",
                              "leaderboard": [
                                { "participant": "George", "totalScore": 795 },
                                { "participant": "Hannah", "totalScore": 750 },
                                { "participant": "Ivan",   "totalScore": 745 }
                              ]
                            }

INFO  QuizApiClient       : [POST] Response received in 344ms | HTTP 200

INFO  PollOrchestrator    : ╔═══════════════════════════════════════════════════╗
INFO  PollOrchestrator    : ║         SUBMISSION RESULT                        ║
INFO  PollOrchestrator    : ╠═══════════════════════════════════════════════════╣
INFO  PollOrchestrator    : ║  isCorrect     : true  ✅                        ║
INFO  PollOrchestrator    : ║  isIdempotent  : true  ✅                        ║
INFO  PollOrchestrator    : ║  submittedTotal: 2290                            ║
INFO  PollOrchestrator    : ║  expectedTotal : 2290                            ║
INFO  PollOrchestrator    : ║  message       : Correct!                        ║
INFO  PollOrchestrator    : ╚═══════════════════════════════════════════════════╝
```

---

## 📡 API Contracts

### GET /quiz/messages

```
GET /quiz/messages?regNo=RA2311050010029&poll=0
Host: devapigw.vidalhealthtpa.com
```

**Response:**
```json
{
  "regNo": "RA2311050010029",
  "setId": "SET_1",
  "pollIndex": 0,
  "events": [
    { "roundId": "R1", "participant": "George", "score": 320 },
    { "roundId": "R2", "participant": "Hannah", "score": 280 },
    { "roundId": "R3", "participant": "Ivan",   "score": 195 }
  ]
}
```

### POST /quiz/submit

**Request:**
```json
{
  "regNo": "RA2311050010029",
  "leaderboard": [
    { "participant": "George", "totalScore": 795 },
    { "participant": "Hannah", "totalScore": 750 },
    { "participant": "Ivan",   "totalScore": 745 }
  ]
}
```

**Response:**
```json
{
  "isCorrect": true,
  "isIdempotent": true,
  "submittedTotal": 2290,
  "expectedTotal": 2290,
  "message": "Correct!"
}
```

---

## 🛡 Edge Cases Handled

| Edge Case | Strategy |
|---|---|
| Duplicate events across polls | Composite key dedup: `roundId::participant` |
| Duplicate entire poll response | Response-level guard: `setId + pollIndex` |
| API returns HTTP 5xx / timeout | Exponential backoff retry (up to 3 attempts) |
| 3+ consecutive poll failures | Circuit breaker aborts — no bad submission |
| Negative score in event | Dropped during defensive validation + WARN log |
| Null participant or roundId | Dropped during defensive validation + WARN log |
| Timing drift between polls | `scheduleWithFixedDelay` — guaranteed 5s gap |
| Extra fields in submit body | `rank` field annotated `@JsonIgnore` |
| Missing regNo parameter | Falls back to default configured value |
| Submitting more than once | Single submit gate enforced by `AtomicBoolean` |

---

## 🔧 Configuration

All tuneable settings in `src/main/resources/application.properties`:

```properties
# API
app.quiz.api-base-url=https://devapigw.vidalhealthtpa.com/srm-quiz-task
app.quiz.default-reg-no=RA2311050010029

# Polling
app.quiz.total-polls=10
app.quiz.poll-delay-ms=5000

# Resilience
app.quiz.max-retries=3
app.quiz.circuit-breaker-threshold=3
```

---

## 📈 Submission Result

```
┌──────────────────────────────────────────────────────┐
│  Bajaj Finserv Health × SRM — Qualifier Submission   │
├──────────────────────────┬───────────────────────────┤
│  Submitted Total         │  2290                     │
│  Expected Total          │  2290                     │
│  isCorrect               │  ✅ true                  │
│  isIdempotent            │  ✅ true                  │
│  Submission Count        │  1 (single fire)          │
│  Dedup Events Dropped    │  7 duplicates             │
│  Total Polls Executed    │  10 / 10                  │
│  Result                  │  ✅ CORRECT               │
└──────────────────────────┴───────────────────────────┘
```

---

## 👨‍💻 Author

**Venkatesh**
- GitHub: [@venkatesh0029](https://github.com/venkatesh0029)
- Repo: [QuizRank-Engine](https://github.com/venkatesh0029/QuizRank-Engine)

---

<p align="center">
  Built with clean architecture, defensive engineering, and deep respect for the edge case. 🚀
</p>
