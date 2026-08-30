# Event-Driven Notification System Architecture

This project is an event-driven, asynchronous notification system built with Spring Boot microservices, Kafka, Redis, PostgreSQL, and Docker Compose.

---

## 1. High-Level System Workflow

`User Request` ➔ **EventPostingService** ➔ **KafkaServer** ➔ **NotificationPostingService** ➔ **Email (SMTP)**

1. **User Request**: Client sends an event request payload to the ingestion endpoint.
2. **EventPostingService**: Authenticates the request, enforces rate-limiting rules, processes the payload, and publishes an event message to Kafka.
3. **KafkaServer**: Acts as an asynchronous message broker, decoupling event ingestion from notification processing.
4. **NotificationPostingService**: Listens to the Kafka topic, consumes incoming event messages, and processes notification tasks.
5. **Email (SMTP)**: Dispatches the final email notifications to recipients using SMTP.

---

## 2. Ingestion Flow (EventPostingService Deep Dive)

`Request` ➔ **AuthenticationService** ➔ **RateLimiter** ➔ **EventPostingService Core**

### Core Components & Data Stores

* **AuthenticationService (PostgreSQL)**:
    * Verifies user identity and permissions.
    * **Configurable**: Can be toggle-disabled in `application.properties:authentication.required` for testing or open environments.
    * Uses **PostgreSQL** to persist user data, credentials, and access roles.

* **RateLimiter (Redis)**:
    * Prevents API abuse and ensures fair resource allocation across clients.
    * **Configurable**: Can be toggle-disabled in `application.properties:rate-limiting.required` for testing or open environments.
    * Utilizes **Redis** as an in-memory key-value store for high-throughput, low-latency counter management and token bucket tracking.

* **EventPostingService Core**:
    * Executes application business logic once authentication and rate-limiting checks pass.
    * Serializes and publishes validated events to the designated **Kafka** topic.

---

## 3. Technology Stack Summary

| Layer / Component | Technology | Purpose |
| :--- | :--- | :--- |
| **API Ingestion** | Spring Boot (`eventposting`) | Handles incoming REST requests, auth, and rate-limiting. |
| **Database** | PostgreSQL | Persistent storage for user authentication and system configuration. |
| **In-Memory Cache** | Redis | High-speed cache for sliding-window / token-bucket rate limiting. |
| **Message Broker** | Apache Kafka | Event streaming broker for asynchronous service communication. |
| **Consumer Service** | Spring Boot (`notificationgenerator`) | Listens to Kafka topics and formats notification messages. |
| **Delivery Engine** | SMTP (JavaMail) | Sends final notification emails to target users. |
| **Orchestration** | Docker Compose | Manages local container networking, environment variables, and storage. |

## 4. Notification Generator Configuration

The `notificationgenerator` microservice functions as an asynchronous worker. It listens to event streams from Apache Kafka, deserializes event payloads, processes business logic, and dispatches formatted email notifications via Mailtrap.

---
