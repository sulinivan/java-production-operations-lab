# Observability, Logging & Audit Trails

To ensure security, operational control, and compliance with data privacy regulations (e.g., GDPR/HIPAA), CloudShare integrates structured logging, telemetry dashboards, and a tamper-evident auditing engine.

---

## 1. Structured Logging

In production, log files are parsed by automated indexers (e.g., ELK Stack, Splunk, Datadog). CloudShare formats all logs in structured **JSON format** instead of clear text to enable advanced filtering and indexing.

### Mapped Diagnostic Context (MDC)
To trace asynchronous requests across the system, every API request is assigned a unique `traceId` at the gateway. This ID is passed to the Spring backend via the `X-Trace-Id` header and bound to the thread execution context using Spring Security filters and SLF4J MDC.

*   **MDC Fields Captured:**
    *   `traceId`: Session/request transaction uuid.
    *   `userId`: UUID of the authenticated user making the request (if logged in).
    *   `clientIp`: IPv4/IPv6 address of the calling client.
    *   `httpMethod`: GET, POST, DELETE, etc.
    *   `requestUri`: Request endpoint path.

### Logback Configuration (`logback-spring.xml`)
The Logback configuration is optimized to output JSON logs to standard output (Console) where containers forward them to indexers.

```xml
<configuration>
    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp>
                    <timeZone>UTC</timeZone>
                </timestamp>
                <logLevel/>
                <loggerName/>
                <threadName/>
                <message/>
                <mdc/> <!-- Automatically serializes all MDC variables -->
                <arguments/>
                <stackTrace>
                    <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
                        <maxDepthPerThrowable>30</maxDepthPerThrowable>
                        <maxLength>2048</maxLength>
                        <shortenedClassNameLength>20</shortenedClassNameLength>
                        <exclude>sun\.reflect\..*</exclude>
                        <exclude>java\.lang\.reflect\..*</exclude>
                        <rootCauseFirst>true</rootCauseFirst>
                    </throwableConverter>
                </stackTrace>
            </providers>
        </encoder>
    </appender>

    <!-- Console logging levels -->
    <root level="INFO">
        <appender-ref ref="JSON_CONSOLE" />
    </root>
    
    <!-- Fine-grained security logs -->
    <logger name="org.springframework.security" level="WARN"/>
    <!-- Note: com.cloudshare level is INFO in production (non-dev profile) to prevent 
         leakage of sensitive identifiers/tokens in logs. Set com.cloudshare to DEBUG 
         only in dev-specific configs. -->
    <logger name="com.cloudshare" level="INFO"/>
</configuration>
```

### Log JSON Output Sample
```json
{
  "@timestamp": "2026-06-23T19:57:04.123Z",
  "level": "INFO",
  "logger_name": "com.cloudshare.service.FileService",
  "thread_name": "http-nio-8080-exec-3",
  "message": "File successfully virus scanned and encrypted.",
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "userId": "e4c278e9-d7bb-4f40-8b6b-352277d33d9c",
  "clientIp": "192.168.1.15",
  "httpMethod": "POST",
  "requestUri": "/api/v1/files/upload",
  "fileId": "7bf3d834-ff4d-4cb0-a548-52fb9882a934",
  "fileSize": 2048576
}
```

### 1.4 Gateway Access Logs (Nginx)
To prevent the accidental logging of credentials or sensitive public link passwords (passed via the `Authorization` or `X-Share-Password` headers), the Nginx edge gateway implements a custom log format that sanitizes or strips these values:

```nginx
# Custom log format stripping sensitive tokens and headers
log_format secure_json escape=json '{'
    '"time_local":"$time_local",'
    '"remote_addr":"$remote_addr",'
    '"request":"$request",'
    '"status": "$status",'
    '"body_bytes_sent":"$body_bytes_sent",'
    '"http_referer":"$http_referer",'
    '"http_user_agent":"$http_user_agent",'
    '"http_x_forwarded_for":"$http_x_forwarded_for",'
    '"trace_id":"$http_x_trace_id",'
    '"auth_header_present":"$auth_header_present",'
    '"share_password_present":"$share_password_present"'
'}';

# Map rules to detect header presence without logging actual values
map $http_authorization $auth_header_present {
    default "true";
    ""      "false";
}

map $http_x_share_password $share_password_present {
    default "true";
    ""      "false";
}

server {
    listen 443 ssl http2;
    access_log /var/log/nginx/access.log secure_json;
}
```
*   **Security Benefit:** Only the presence (`true`/`false`) of the authentication and sharing headers is logged, ensuring that plain-text passwords or bearer tokens are never saved to disk in Nginx access logs.

---

## 2. Tamper-Evident Audit Trails

While debug logs are transient, **Audit Logs** represent historical records of user interactions. They must be stored in PostgreSQL and structured to prevent tempering.

### 2.1 Audit Record Data Layout
Every auditable action records:
*   `Who`: User ID (or system/guest identifier).
*   `What`: The action name (`LOGIN_ATTEMPT`, `LOGIN_SUCCESS`, `FILE_UPLOAD`, `FILE_DOWNLOAD`, `SHARE_CREATED`, `SHARE_REVOKED`, `PASSWORD_RESET`).
*   `When`: ISO 8601 UTC timestamp.
*   `Where`: Caller IP Address, user agent.
*   `Target Resource`: File ID, Shared Link ID, or affected User ID.
*   `Outcome`: Status (SUCCESS, FAIL, BLOCKED).

### 2.2 Security / Tamper Protections
1.  **Append-Only Privileges:** 
    *   The database account used by the Spring Boot application is granted only `SELECT` and `INSERT` privileges on the `audit_logs` table.
    *   `UPDATE` and `DELETE` privileges are strictly revoked. This prevents application compromises from cleaning up malicious footprints.
2.  **Cryptographic Chaining (Optional Compliance Feature):**
    *   For high-security compliance, each audit log entry can contain a SHA-256 hash checksum generated by combining the current record fields with the SHA-256 hash of the *previous* audit log entry.
    *   If a database administrator alters a row, the checksum chain breaks, exposing the tamper event during automated audits.

---

## 3. Telemetry & Metrics (Actuator + Prometheus)

CloudShare exposes real-time application statistics via Spring Boot Actuator endpoints. These endpoints are gathered by a Prometheus server and rendered in a Grafana dashboard.

### Spring Actuator Properties (`application.yml`)
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true # Exposes liveness and readiness endpoints separately
  metrics:
    tags:
      application: ${spring.application.name}
```

### Critical Performance Metrics Monitored

1.  **System Health:**
    *   `up`: Basic target availability.
    *   `system_cpu_usage`: JVM node CPU utilization percentage.
2.  **Storage / IO latency:**
    *   `disk_free_bytes`: Track remaining space on local host storage.
    *   `http_client_requests_seconds`: Latency of out-of-band requests (e.g., S3 client API/MinIO calls, ClamAV connection speed).
3.  **Application Throughput:**
    *   `http_server_requests_seconds_count`: Total requests processed.
    *   `http_server_requests_seconds_bucket`: Latency distribution. (Triggers alerts if 95th percentile latency exceeds 500ms).
4.  **Database Connection Pool (HikariCP):**
    *   `hikaricp_connections_active`: Connections currently in use by queries.
    *   `hikaricp_connections_pending`: Queries waiting for an available DB connection (triggers alerts if > 5 for over 30s).
5.  **JVM Diagnostics:**
    *   `jvm_memory_used_bytes`: Current memory footprint.
    *   `jvm_gc_pause_seconds`: Duration of Garbage Collection stops.
