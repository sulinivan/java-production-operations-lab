# Threat Modeling & Risk Assessment

This document maps out the threat landscape for the CloudShare application using the **STRIDE** methodology (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege) and specifies the engineering countermeasures.

---

## 1. Trust Boundaries & Asset Identification

We identify trust boundaries where data crosses from untrusted sources (e.g., client browsers, external integrations) into our managed network components.

```
[ User Browser / Public Client ]
               |
  ================= TRUST BOUNDARY 1 (Internet to Edge) =================
               v
     [ Nginx Reverse Proxy ]
               |
  ========= TRUST BOUNDARY 2 (Edge to Internal App Network) =========
               v
     [ Spring Boot Application Cluster ]
               |
     +---------+---------+---------+
     |                   |         |
     v                   v         v
[PostgreSQL DB]    [Redis Cache]  [MinIO/Local Storage]  [ClamAV]
```

### Core Assets to Protect:
1.  **User Credentials:** Passwords, MFA keys, email addresses.
2.  **User Content (Files):** Stored binary objects.
3.  **Cryptographic Secrets:** JWT keys, File Encryption Keys (FEKs).
4.  **Operational Metadata:** File listings, ownership records, sharing settings.
5.  **Audit Logs:** Records of who accessed what files and when.

---

## 2. STRIDE Threat Analysis & Countermeasures

### 2.1 Spoofing (Identity Theft)
*   **Threat:** A hacker intercepts or guesses a user session token to impersonate them and access private documents.
*   **Countermeasures:**
    *   Passwords encrypted using `BCrypt` with a cost factor of 12.
    *   Strict session validation using **stateless JWTs** and **Refresh Token Rotation (RTR)**.
    *   Multi-Factor Authentication (MFA) using standard TOTP algorithms.
    *   All cookies set with `Secure`, `HttpOnly`, and `SameSite=Strict` flags.

### 2.2 Tampering (Data Modification)
*   **Threat:** An attacker intercepts an upload stream to inject code, or modifies files sitting on the disk storage.
*   **Countermeasures:**
    *   **Envelope Encryption:** Files are encrypted using **AES-256-GCM** before writing to storage. GCM mode includes an authentication tag, preventing offline tempering with the ciphertext.
    *   **SHA-256 Checksums:** The SHA-256 checksum of every uploaded file is calculated and saved in the DB metadata. Downloads verify that the decrypted file matches the original checksum.
    *   **SSL/TLS 1.3:** Encrypts data in transit to prevent Man-in-the-Middle (MitM) packet tempering.

### 2.3 Repudiation (Action Denial)
*   **Threat:** A malicious user uploads malware or downloads unauthorized files, then claims "it wasn't me" because there are no tracking records.
*   **Countermeasures:**
    *   **Structured Audit Trail:** Real-time logging of user activity into PostgreSQL.
    *   **Trace ID Injection:** Every request is tagged with a UUID `traceId` which is logged by all components.
    *   **Append-Only Permissions:** The database connection profile used by the web app cannot modify or delete audit rows, ensuring log integrity.

### 2.4 Information Disclosure (Data Leakage)
*   **Threat:** An attacker runs directory traversal searches (e.g. `../../config/application.properties`) or scrapes raw storage volumes.
*   **Countermeasures:**
    *   **UUID Mapping:** Files are stored using random UUID filenames in the storage container, isolating them from their user-facing metadata.
    *   **Path Sanitization:** Filenames are stripped of path modifiers (`../`, `..\\`) during upload handling.
    *   **Content-Disposition Headers:** Downloads force `Content-Type: application/octet-stream` and `Content-Disposition: attachment` to prevent SVG/HTML scripts from running inline in the user's browser (cross-site scripting vector).

### 2.5 Denial of Service (Service Interruption)
*   **Threat:** An attacker floods the system with massive file uploads or repeatedly queries complex search APIs, exhausting server RAM or database connection pools.
*   **Countermeasures:**
    *   **Rate Limiting:** Redis-based token bucket rate limiters filter IP ranges and user profiles.
    *   **Request Size Constraints:** Strict limits enforced at Nginx (`client_max_body_size`) and Spring Boot configuration levels.
    *   **Streaming I/O:** Uploads stream directly to storage rather than loading the entire file into Java heap RAM.

### 2.6 Elevation of Privilege (Unauthorized Access)
*   **Threat:** A standard user modifies the URL parameter `fileId` to download or delete files belonging to another user.
*   **Countermeasures:**
    *   **Role-Based Access Control (RBAC):** Spring Security filters validate roles (`ROLE_USER`, `ROLE_ADMIN`) on all API endpoints.
    *   **Object-Level Ownership Validation:** The query engine verifies ownership on every file request:
        ```sql
        SELECT * FROM files WHERE id = :fileId AND owner_id = :currentUserId AND deleted = FALSE;
        ```
