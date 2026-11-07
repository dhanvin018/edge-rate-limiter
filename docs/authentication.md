# Authentication Module Technical Documentation

This document details the implementation of the authentication subsystem within the `eventposting` microservice.

---

## 1. Overview

The `authentication` module provides configurable access control, JWT generation/validation, user persistence layer abstractions, and security filter integration.

---

## 2. Component Implementation Details

### Security & Filter Configuration (`SecurityConfig.java`)
* **Configurable Enforcement**: Reads the property `authentication.required` (defaults to `true`). When set to `false`, security permits all requests (`auth.anyRequest().permitAll()`), and the service trusts identity headers set by an upstream gateway. See the README's trust model section for the conditions this mode needs.
* **Stateless Session Management**: Configures Spring Security with `SessionCreationPolicy.STATELESS`, disabling HTTP sessions and CSRF protection (`AbstractHttpConfigurer::disable`) for REST API scalability.
* **Public Matchers**: Publicly exposes `/api/v1/auth/**`, `/error`, and `/actuator/**` endpoints while requiring authentication for all other routes when enabled.
* **Rate-Limit Filter Order**: Registers `RateLimitFilter` before `UsernamePasswordAuthenticationFilter`. With authentication required, the filter takes identity only from a valid JWT and sets it in the security context. In proxy mode it rejects requests without a valid JWT with `401`, because proxied requests never reach Spring Security's authorization step.
* **Password Hashing**: Registers a `BCryptPasswordEncoder` bean for secure user password storage.

---

### Token Management & Claims Parsing (`JwtService.java`)
* **Cryptographic Signing**: Initializes HMAC-SHA keys using `Keys.hmacShaKeyFor()` from `jwt.secret`, which is read from the `JWT_SECRET` environment variable and must be at least 32 bytes.
* **Token Generation**: Constructs signed JWTs valid for 24 hours (`86,400,000 ms`), embedding the `username` as the token subject and appending custom claims such as `tier`.
* **Claims Extraction**: Parses signed claims (`verifyWith(key)`) to safely extract subjects and convert custom string claims into strongly-typed `UserTier` enums.
* **Token Validation**: Catches `JwtException` instances to validate signatures and expiration timestamps, returning boolean validation statuses.

---

### Data Persistence Layer (`UserRepository.java`)
* **JPA Repository Integration**: Extends `JpaRepository<User, Long>` to interface directly with the PostgreSQL backing database. Loaded only when `authentication.required=true`; gateway mode runs without a database.
* **Query Execution**: Defines derived finder methods:
    * `findByUsername(String username)`: Retrieves user entities for credential verification.
    * `existsByUsername(String username)`: Evaluates user existence during registration routines.

---

### Controllers & Data Transfer Objects (`controller/` & `model/`)
* **`AuthController.java`**: Exposes `/api/v1/auth/signup` and `/api/v1/auth/login`, which return JWTs. Loaded only when `authentication.required=true`.
* **Data Transfer Objects**:
    * `AuthRequest.java`: Encapsulates inbound credentials (`username`, `password`).
    * `AuthResponse.java`: Encapsulates outbound JWT responses.
* **Domain Models**:
    * `User.java`: Entity representing database records and security identity.
    * `UserTier.java`: Enum defining rate-limiting tier classifications associated with the user.
