# BankRadar — DigitalOcean App Platform Deployment Notes

## Architecture

DigitalOcean App Platform should run only the Spring Boot application.
PostgreSQL, Redis/Valkey and Kafka should be external managed services.

```text
DigitalOcean App Platform
        │
        ▼
BankRadar Spring Boot App
        │
 ┌──────┼──────┐
 ▼      ▼      ▼
Postgres Redis Kafka
```

## App Platform build/run commands

Build command:

```bash
./gradlew clean bootJar -x test
```

Run command:

```bash
java -jar build/libs/*.jar
```

## Required App Platform environment variables

```text
SPRING_PROFILES_ACTIVE=appplatform

JWT_SECRET=bankradar-production-secret-2026-change-me

GROK_API_KEY=your_groq_api_key
GROK_MODEL=llama-3.1-8b-instant
GROK_API_URL=https://api.groq.com/openai/v1/chat/completions

DB_HOSTNAME=your-postgres-host
DB_PORT=25060
DB_DATABASE=bankradar
DB_USERNAME=doadmin
DB_PASSWORD=your-postgres-password

REDIS_HOST=your-redis-host
REDIS_PORT=6379
REDIS_PASSWORD=your-redis-password

KAFKA_BOOTSTRAP_SERVERS=your-kafka-bootstrap-server
KAFKA_USERNAME=your-kafka-username
KAFKA_PASSWORD=your-kafka-password
KAFKA_GROUP_ID=bankradar-risk-group
```

Mark these values as encrypted/secret in App Platform:

```text
JWT_SECRET
GROK_API_KEY
DB_PASSWORD
REDIS_PASSWORD
KAFKA_PASSWORD
```

## Where to add environment variables

DigitalOcean → App Platform → your app → Settings → Environment Variables → Edit → Add Variable.

Use app-level variables unless you split the project into multiple App Platform components.

## Managed services

### PostgreSQL
Use DigitalOcean Managed PostgreSQL.
Copy host, port, database name, username and password into:

```text
DB_HOSTNAME
DB_PORT
DB_DATABASE
DB_USERNAME
DB_PASSWORD
```

### Redis
You already use Upstash Redis.
Copy endpoint/host, port and token/password into:

```text
REDIS_HOST
REDIS_PORT
REDIS_PASSWORD
```

TLS is enabled in `application-appplatform.yml`.

### Kafka
Use DigitalOcean Managed Kafka or Confluent Cloud.
Create topic:

```text
bankradar.transactions
```

Then set:

```text
KAFKA_BOOTSTRAP_SERVERS
KAFKA_USERNAME
KAFKA_PASSWORD
KAFKA_GROUP_ID=bankradar-risk-group
```

## Groq API key setup

The frontend never stores or sends the API key directly to Groq.
The flow is:

```text
Frontend → /api/ai/chat → Backend → GroqCloud
```

For local terminal run:

```bash
export JWT_SECRET=change-me-in-production-min-32-chars-long
export GROK_API_KEY=your_groq_api_key
export GROK_MODEL=llama-3.1-8b-instant
./gradlew bootRun
```

For IntelliJ Run button:

```text
Run → Edit Configurations → BankRadarApplication → Environment variables
```

Add:

```text
JWT_SECRET=change-me-in-production-min-32-chars-long;GROK_API_KEY=your_groq_api_key;GROK_MODEL=llama-3.1-8b-instant
```

## Local Docker

Run full local stack:

```bash
docker compose up -d --build
```

Check app logs:

```bash
docker logs bankradar-app --tail 100
```

Check health:

```bash
curl http://localhost:8080/actuator/health
```
