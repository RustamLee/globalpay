# GlobalPay
**GlobalPay** is a production-minded payment engine built with **Java 21**, **Spring Boot 3.4**, **PostgreSQL**, and **Redis**.
The project focuses on the hard parts of money movement systems:
- **concurrency safety**
- **idempotent request handling**
- **transaction consistency**
- **failure-aware state management**
- **real integration testing with containers**
This repository is designed as a strong **backend portfolio project / MVP payment core** rather than a simple CRUD demo.
---
## Why this project is interesting
Payment systems are not difficult because of REST controllers — they are difficult because of **race conditions**, **duplicate requests**, **partial failures**, and **state consistency**.
GlobalPay addresses those concerns with a pragmatic architecture:
- **Pessimistic locking** on accounts to protect balance updates
- **Two-layer idempotency** using Redis + database state
- **Transactional status flow** with `PENDING -> PROCESSING -> SUCCESS | FAILED`
- **`afterCommit` cache synchronization** so side effects happen only after a successful database commit
- **Retry support** for optimistic locking conflicts
- **Testcontainers-based integration tests** for realistic verification
---
## Core capabilities
### Money transfer API
The current HTTP API exposes:
- `POST /api/v1/transfer` — execute a transfer
- `GET /api/v1/transfer/accounts/{accountId}/transactions` — get paginated transaction history
### Reliability patterns implemented
- **Pessimistic DB locking** via `SELECT ... FOR UPDATE`
- **Unique idempotency key protection** at the database layer
- **Fast-path replay handling** via Redis cache
- **Failure persistence** for business exceptions
- **Separation of orchestration and business logic** between `TransferService` and `MoneyTransferProcessor`
- **Centralized exception mapping** with meaningful HTTP responses
- **Request correlation** through MDC / request IDs
- **Metrics exposure** through Spring Boot Actuator + Prometheus
---
## Architecture at a glance
```text
Client
  |
  v
TransferController
  |
  v
TransferService
  |-- Redis: completed idempotency cache
  |-- TransactionRepository: insert-if-absent + transaction row lock
  |
  v
MoneyTransferProcessor
  |-- AccountRepository: pessimistic account locks
  |-- TransactionRepository: status updates
  |
  v
PostgreSQL
```
### Transfer flow summary
1. Check Redis for a completed idempotency key
2. Insert a `PENDING` transaction row if it does not exist
3. Lock the transaction row by idempotency key
4. Move state to `PROCESSING`
5. Lock both accounts with pessimistic write locks
6. Validate business rules
7. Update balances and persist `SUCCESS`
8. Cache completion in Redis **after transaction commit**
---
## Tech stack
### Backend
- Java 21
- Spring Boot 3.4
- Spring Web
- Spring Data JPA
- Spring Validation
- Spring Retry
- Spring Data Redis
- Spring Boot Actuator
- SpringDoc OpenAPI
### Data & infrastructure
- PostgreSQL
- Redis
- Flyway
- Docker Compose
- Prometheus
- Grafana
### Testing
- JUnit 5
- Mockito
- Testcontainers
- Maven Surefire / Failsafe
- JaCoCo
---
## Project structure
```text
GlobalPay/
├── src/main/java/org/example/global_pay/
│   ├── controller/
│   ├── domain/
│   ├── dto/
│   ├── exception/
│   ├── filter/
│   ├── repository/
│   └── service/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
├── src/test/java/org/example/global_pay/
│   ├── controller/
│   ├── domain/
│   └── service/
├── docker-compose.yml
├── pom.xml
└── README.md
```
---
## Local setup
### Prerequisites
Make sure you have:
- **Java 21**
- **Maven 3.9+**
- **Docker** (required for local infrastructure and integration tests)
### 1) Start local infrastructure
This project includes PostgreSQL, Redis, Prometheus, and Grafana in `docker-compose.yml`.
```bash
cd GlobalPay
docker compose up -d
```
Services defined in the project:
- PostgreSQL: `localhost:5432`
- pgAdmin: `localhost:5050`
- Redis: `localhost:6379`
- Prometheus: `localhost:9090`
- Grafana: `localhost:3000`
### 2) Run the application
```bash
cd GlobalPay
mvn spring-boot:run
```
The application is configured in `src/main/resources/application.yml` to connect to:
- PostgreSQL: `jdbc:postgresql://localhost:5432/globalpay`
- Redis: `localhost:6379`
Flyway migrations run automatically on startup.
### 3) Open API docs
If the app is running locally, SpringDoc should expose:
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
---
## Creating sample data for manual testing
The current project focuses on the **transfer engine**, so accounts and users should already exist before calling the transfer endpoint.
You can create sample records directly in PostgreSQL.
### Option A — open `psql` inside the container
```bash
docker exec -it globalpay-db psql -U devuser -d globalpay
```
Then run:
```sql
INSERT INTO users (id, email)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'alice@globalpay.dev'),
  ('22222222-2222-2222-2222-222222222222', 'bob@globalpay.dev');
INSERT INTO accounts (id, user_id, balance, currency)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 1000.00, 'USD'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', 250.00, 'USD');
```
### Option B — one-shot SQL command
```bash
docker exec -i globalpay-db psql -U devuser -d globalpay <<'SQL'
INSERT INTO users (id, email)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'alice@globalpay.dev'),
  ('22222222-2222-2222-2222-222222222222', 'bob@globalpay.dev')
ON CONFLICT (email) DO NOTHING;
INSERT INTO accounts (id, user_id, balance, currency)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 1000.00, 'USD'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', 250.00, 'USD')
ON CONFLICT (id) DO NOTHING;
SQL
```
---
## API examples
### Execute a transfer
```bash
curl -X POST 'http://localhost:8080/api/v1/transfer' \
  -H 'Content-Type: application/json' \
  -d '{
    "fromId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "toId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    "amount": 100.00,
    "idempotencyKey": "33333333-3333-3333-3333-333333333333"
  }'
```
Expected success response:
```text
Transfer successful
```
### Fetch transaction history
```bash
curl 'http://localhost:8080/api/v1/transfer/accounts/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/transactions?page=0&size=10&sort=createdAt,desc'
```
### Example business error scenarios
The API is designed to return meaningful responses for situations such as:
- insufficient funds
- self-transfer attempts
- currency mismatch
- duplicate idempotency keys
- account not found
- lock / concurrency conflicts
---
## Transfer request contract
`POST /api/v1/transfer`
```json
{
  "fromId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "toId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  "amount": 100.00,
  "idempotencyKey": "33333333-3333-3333-3333-333333333333"
}
```
### Validation rules
- `fromId` — required
- `toId` — required
- `amount` — required, positive, max `100000.00`, up to 2 decimal places
- `idempotencyKey` — required
---
## Database model
### `users`
Stores account owners.
### `accounts`
Stores balance, currency, and optimistic lock version.
### `transactions`
Stores transfer state and idempotency metadata.
Important transaction fields:
- `status`
- `idempotency_key`
- `failure_reason`
- `created_at`
- `updated_at`
Flyway migrations live in `src/main/resources/db/migration/`.
---
## Testing
### Run unit tests
```bash
cd GlobalPay
mvn test
```
### Run the full verification pipeline
This runs unit tests, integration tests, and JaCoCo reporting.
```bash
cd GlobalPay
mvn verify
```
### Notes about the test strategy
The project includes:
- **unit tests** for domain logic, controller behavior, and service orchestration
- **integration tests** with **Testcontainers** for PostgreSQL and Redis
- **concurrency-oriented scenarios** to verify idempotency and balance consistency
Integration tests require Docker because Testcontainers starts real infrastructure.
---
## Observability
### Health and metrics
Spring Boot Actuator exposes:
- `GET /actuator/health`
- `GET /actuator/prometheus`
### Prometheus
If you start the provided compose stack:
- Prometheus: `http://localhost:9090`
### Grafana
If you start the provided compose stack:
- Grafana: `http://localhost:3000`
- Default admin password in compose: `admin`
---
## Engineering decisions worth highlighting
### 1. Pessimistic locking for balance safety
`AccountRepository` uses pessimistic write locks so concurrent transfers cannot corrupt balances.
### 2. Two-layer idempotency
GlobalPay uses:
- Redis for a fast replay response
- database transaction state for atomic correctness
### 3. State-driven transaction lifecycle
Transactions move through explicit states (`PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`), making outcomes auditable and easier to reason about.
### 4. Failure-aware transaction handling
Business exceptions are preserved in transaction state instead of disappearing behind a rollback.
### 5. Side effects after commit
Redis is updated only after a successful commit, preventing cache/database drift.
---
## Current limitations / roadmap
This repository is already strong as an MVP payment core, but the next logical improvements are:
- ordered lock acquisition to further reduce circular deadlock risk
- audit log persistence
- outbox pattern for downstream events / integrations
- rate limiting at the API edge
- richer account/user management APIs
- broader multi-currency support
---
## Portfolio / interview angle
GlobalPay is a good project to discuss in interviews because it demonstrates:
- practical payment system thinking
- transactional consistency under concurrency
- API design for real-world failure modes
- Spring Boot architecture beyond CRUD
- realistic testing with containers instead of pure mocks
---
## License
No license file is currently present in the root project.
If you plan to publish this repository publicly, adding a license (for example MIT or Apache-2.0) would be a good next step.
