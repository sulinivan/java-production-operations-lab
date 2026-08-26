package com.cloudshare.performance;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class CloudShareLoadTest extends Simulation {

    private static final String FILE_PATH = "src/test/resources/data/10mb_random.txt";
    private static final int FILE_SIZE = 10 * 1024 * 1024; // 10MB

    static {
        generateRandomFile();
        if (Boolean.parseBoolean(System.getProperty("gatling.insecure", "false"))) {
            System.setProperty("gatling.ssl.useInsecureTrustManager", "true");
        }
    }

    private static void generateRandomFile() {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[1024 * 64]; // 64KB buffer
                SecureRandom random = new SecureRandom();
                int bytesWritten = 0;
                while (bytesWritten < FILE_SIZE) {
                    for (int i = 0; i < buffer.length; i++) {
                        buffer[i] = (byte) (random.nextInt(95) + 32); // random printable ASCII
                    }
                    int toWrite = Math.min(buffer.length, FILE_SIZE - bytesWritten);
                    fos.write(buffer, 0, toWrite);
                    bytesWritten += toWrite;
                }
                System.out.println("Generated 10MB random text file at: " + file.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException("Failed to generate random file for load testing", e);
            }
        }
    }

    // Feeder to generate unique user credentials at runtime per virtual user
    private final Iterator<Map<String, Object>> userFeeder = new Iterator<>() {
        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Map<String, Object> next() {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            return Map.of(
                "username", "load_user_" + suffix,
                "email", "load_user_" + suffix + "@example.com",
                "password", "LoadPass123!"
            );
        }
    };

    private final String baseUrl = System.getProperty("gatling.baseUrl", "https://localhost");
    @SuppressWarnings("unused")
    private final boolean insecure = Boolean.parseBoolean(System.getProperty("gatling.insecure", "false"));
    private final int p95ApiMs = Integer.parseInt(System.getProperty("gatling.p95ApiMs", "200"));
    private final int p95StreamMs = Integer.parseInt(System.getProperty("gatling.p95StreamMs", "1500"));

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .acceptHeader("application/json")
        .userAgentHeader("Gatling Load Test");

    private final ScenarioBuilder scn = scenario("CloudShare Load Test Scenario")
        .feed(userFeeder)
        // 1. Register
        .exec(http("Register User")
            .post("/api/v1/auth/register")
            .header("Content-Type", "application/json")
            .body(StringBody("{\"username\":\"#{username}\",\"email\":\"#{email}\",\"password\":\"#{password}\"}"))
            .check(status().is(201))
        )
        // 2. Login
        .exec(http("Login User")
            .post("/api/v1/auth/login")
            .header("Content-Type", "application/json")
            .body(StringBody("{\"usernameOrEmail\":\"#{username}\",\"password\":\"#{password}\"}"))
            .check(status().is(200))
            .check(jsonPath("$.data.accessToken").saveAs("token"))
        )
        // 3. Upload 10MB File
        .exec(http("Upload 10MB File")
            .post("/api/v1/files/upload")
            .header("Authorization", "Bearer #{token}")
            .asMultipartForm()
            .bodyPart(
                RawFileBodyPart("file", "data/10mb_random.txt")
                    .contentType("text/plain")
                    .fileName("10mb_random.txt")
            )
            .check(status().is(201))
            .check(jsonPath("$.data.id").saveAs("fileId"))
        )
        // 4. List Files
        .exec(http("List Files")
            .get("/api/v1/files")
            .header("Authorization", "Bearer #{token}")
            .check(status().is(200))
        )
        // 5. Download File
        .exec(http("Download File")
            .get("/api/v1/files/#{fileId}/download")
            .header("Authorization", "Bearer #{token}")
            .check(status().is(200))
        );

    {
        setUp(
            scn.injectOpen(rampUsers(100).during(Duration.ofSeconds(30)))
        ).protocols(httpProtocol)
         .assertions(
             global().failedRequests().percent().lt(0.1),
             // p95 latency for REST API calls < p95ApiMs
             details("Register User").responseTime().percentile3().lt(p95ApiMs),
             details("Login User").responseTime().percentile3().lt(p95ApiMs),
             details("List Files").responseTime().percentile3().lt(p95ApiMs),
             // p95 latency for 10MB file stream upload/download < p95StreamMs
             details("Upload 10MB File").responseTime().percentile3().lt(p95StreamMs),
             details("Download File").responseTime().percentile3().lt(p95StreamMs)
         );
    }
}
