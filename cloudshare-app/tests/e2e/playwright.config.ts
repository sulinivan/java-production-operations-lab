import { defineConfig, devices } from '@playwright/test';

/**
 * CloudShare E2E configuration.
 *
 * Runs against a live Docker Compose stack (Nginx gateway on :443 with a
 * self-signed cert, backed by the real Spring Boot app, Postgres, Redis,
 * ClamAV, and MinIO). This is intentionally NOT mocked - the point of this
 * suite is to catch integration issues the unit/API layers miss.
 *
 * Single worker: several specs (admin role promotion, share revocation)
 * mutate shared Postgres/Redis state via direct SQL and would conflict if
 * run concurrently against the same stack.
 */
export default defineConfig({
    testDir: './specs',
    fullyParallel: false,
    workers: 1,
    retries: process.env.CI ? 1 : 0,
    timeout: 60_000,
    expect: {
        timeout: 7_000,
    },
    reporter: [
        ['html', { outputFolder: 'playwright-report', open: 'never' }],
        ['list'],
    ],
    use: {
        baseURL: process.env.E2E_BASE_URL || 'https://localhost',
        ignoreHTTPSErrors: true,
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        video: 'retain-on-failure',
        actionTimeout: 15_000,
        navigationTimeout: 30_000,
    },
    projects: [
        {
            name: 'chromium',
            use: { ...devices['Desktop Chrome'] },
        },
    ],
});