# Docker & Docker Compose Technical Reference

---

## 1. Core Docker Concepts

### Networking & Service Discovery
* **Bridge Networks**: Placing containers into an isolated virtual network (e.g., `app-network`) allows them to communicate securely without exposing every port to the host machine.
* **DNS-Based Service Discovery**: Within a custom bridge network, Docker runs an internal DNS server. Containers resolve each other using service names (e.g., `postgres:5432`, `kafka:9092`, `redis:6379`) instead of static IP addresses.
* **Port Mapping (`ports`)**: Defined as `HOST_PORT:CONTAINER_PORT` (e.g., `8081:8080`). This bridges host network traffic to container ports, enabling external access while avoiding host port collisions.
* **Host Gateway / Host Mode**: Using `network_mode: "host"` or `host.docker.internal` allows containers to bypass network isolation and target services running natively on the host machine.

### Storage & Persistence
* **Named Volumes**: Declared under the top-level `volumes:` block (e.g., `postgres-data`), volumes exist independently of container lifecycles to preserve data across container restarts and rebuilds.
* **Bind Mounts**: Maps a host file system directory directly into a container path (e.g., `./eventposting/logs:/app/logs`) for persistent file storage or live debugging.

### Build Engine & Architecture
* **Multi-Stage Builds**: Uses multiple `FROM` instructions to isolate the build environment (heavy JDK/Maven image) from the runtime environment (lightweight JRE/Alpine image), reducing final image size and attack surface area.
* **Build Context (`context`)**: Specifies the root directory (e.g., `./eventposting`) sent to the Docker daemon to assemble the container image.

### Container Lifecycle & Health
* **Health Checks (`healthcheck`)**: Commands executed inside a container (e.g., `pg_isready` or `redis-cli ping`) to monitor application readiness beyond process status.
* **Dependency Sequencing (`depends_on`)**: Controls startup order, ensuring application containers defer startup until infrastructure services satisfy `service_healthy` conditions.

---

## 2. Dockerfile Syntax & Directives

| Directive | Description | Example |
| :--- | :--- | :--- |
| `FROM` | Sets the base image and begins a new build stage. | `FROM maven:3.9.6-eclipse-temurin-21 AS builder` |
| `WORKDIR` | Sets the working directory for subsequent instructions. | `WORKDIR /app` |
| `COPY` | Copies files from the host or a prior build stage into the image. | `COPY --from=builder /app/target/*.jar app.jar` |
| `RUN` | Executes build-time commands to produce new image layers. | `RUN mvn package -DskipTests -B` |
| `USER` | Switches execution from `root` to a non-privileged system user. | `USER appuser` |
| `EXPOSE` | Documents the port on which the container listens at runtime. | `EXPOSE 8080` |
| `ENTRYPOINT` | Sets the default command executed when the container starts. | `ENTRYPOINT ["java", "-jar", "app.jar"]` |

---

## 3. Docker Compose Syntax (`docker-compose.yml`)

### Top-Level Structure
```yaml
version: '3.8'

services:
  # Container workload definitions go here

volumes:
  # Named persistent volumes go here

networks:
  # Custom bridge/overlay network definitions go here