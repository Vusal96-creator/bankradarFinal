# BankRadar — Run and Test

## Local infrastructure only

```bash
docker compose up -d postgres redis kafka
```

Then run Spring Boot from IntelliJ or terminal:

```bash
./gradlew bootRun
```

## Full Docker stack

```bash
docker compose up -d --build
```

## Health check

```bash
curl http://localhost:8080/actuator/health
```

Expected: `status: UP`.

## Demo users

Passwords are not shown in the frontend. Use these for local/demo login:

```text
leyla.hasanova     / Analyst123!
kamran.rzayev      / Analyst123!
farid.mammadov     / Manager123!
nigar.valiyeva     / Manager123!
narmin.aliyeva     / Admin123!
rashad.karimli     / Admin123!
system.gateway     / System123!
core.processor     / System123!
```

## Swagger login body

```json
{
  "username": "nigar.valiyeva",
  "password": "Manager123!"
}
```

## Database check

```sql
SELECT username, role, enabled FROM app_users;
SELECT username, password_hash FROM app_users;
SELECT COUNT(*) FROM transactions;
SELECT risk_status, COUNT(*) FROM transactions GROUP BY risk_status;
```

Passwords in `app_users.password_hash` should start with `$2a$`, `$2b$`, or `$2y$`.
