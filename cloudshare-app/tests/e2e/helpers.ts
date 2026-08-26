import { type Page, type BrowserContext, expect } from '@playwright/test';
import { createHash, randomBytes } from 'node:crypto';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

export interface TestUser {
    username: string;
    email: string;
    password: string;
}

const DEFAULT_PASSWORD = 'E2eTest!Passw0rd#1';

/**
 * Generates a unique, valid test user. Unique per call so specs never
 * collide on username/email uniqueness constraints, even across retries.
 */
export function makeTestUser(prefix: string): TestUser {
    const suffix = randomBytes(5).toString('hex');
    const username = `${prefix}${suffix}`.slice(0, 30);
    return {
        username,
        email: `${username}@e2e.cloudshare.test`,
        password: DEFAULT_PASSWORD,
    };
}

/* --- Auth flows --- */

export async function registerUser(page: Page, user: TestUser): Promise<void> {
    await page.goto('/#register');
    await page.locator('#register-username').fill(user.username);
    await page.locator('#register-email').fill(user.email);
    await page.locator('#register-password').fill(user.password);
    await page.locator('#register-form button[type="submit"]').click();

    // On success the app switches back to the login card with the username
    // prefilled - this is the real success signal (register never logs a
    // user in automatically).
    await expect(page.locator('#auth-login-card')).not.toHaveClass(/hidden/);
    await expect(page.locator('#login-username')).toHaveValue(user.username);
}

export async function loginUser(page: Page, user: TestUser, mfaCode?: string): Promise<void> {
    await page.goto('/#login');
    await page.locator('#login-username').fill(user.username);
    await page.locator('#login-password').fill(user.password);
    if (mfaCode) {
        await page.locator('#login-mfa').fill(mfaCode);
    }
    await page.locator('#login-form button[type="submit"]').click();

    await expect(page.locator('#app-shell')).not.toHaveClass(/hidden/);
    await expect(page.locator('#view-dashboard')).not.toHaveClass(/hidden/);
}

export async function registerAndLogin(page: Page, user: TestUser): Promise<void> {
    await registerUser(page, user);
    await loginUser(page, user);
}

export async function logout(page: Page): Promise<void> {
    await page.locator('#nav-logout-btn').click();
    await expect(page.locator('#auth-gateway')).not.toHaveClass(/hidden/);
}

/* --- MFA --- */

export async function readMfaSecret(page: Page): Promise<string> {
    const secretLocator = page.locator('#mfa-secret-text');
    await expect(secretLocator).not.toHaveText('', { timeout: 10_000 });
    return (await secretLocator.textContent())!.trim();
}

/* --- Toast assertions --- */

export async function expectToast(page: Page, text: string | RegExp, type?: 'success' | 'danger' | 'info' | 'warning'): Promise<void> {
    const selector = type ? `.toast.${type}` : '.toast';
    await expect(page.locator(selector, { hasText: text }).first()).toBeVisible({ timeout: 7_000 });
}

/* --- File fixtures --- */

export interface TempFile {
    path: string;
    name: string;
    sha256: string;
}

/**
 * Creates a small, unique, plainly-non-malicious text fixture file for
 * upload/download tests. Content is randomized per call so ClamAV scans
 * and dedup logic never see the same bytes twice across a suite run.
 */
export function createTempFile(baseName = 'e2e-fixture'): TempFile {
    const dir = mkdtempSync(path.join(tmpdir(), 'cloudshare-e2e-'));
    const name = `${baseName}-${randomBytes(4).toString('hex')}.txt`;
    const filePath = path.join(dir, name);
    const content = `CloudShare E2E fixture file\ngenerated=${new Date().toISOString()}\nnonce=${randomBytes(16).toString('hex')}\n`;
    writeFileSync(filePath, content, 'utf-8');

    return {
        path: filePath,
        name,
        sha256: createHash('sha256').update(readFileSync(filePath)).digest('hex'),
    };
}

export function sha256Of(filePath: string): string {
    return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

/* --- Dashboard table helpers --- */

export function fileRow(page: Page, filename: string) {
    return page.locator('#files-tbody tr').filter({ hasText: filename });
}

export async function uploadFileViaUI(page: Page, filePath: string): Promise<void> {
    await page.locator('#file-input').setInputFiles(filePath);
}

/**
 * Clicks the download action for a given file row and resolves once the
 * browser download completes, saving it to a temp path.
 */
export async function downloadFileViaUI(page: Page, filename: string): Promise<string> {
    const row = fileRow(page, filename);
    const [download] = await Promise.all([
        page.waitForEvent('download'),
        row.locator('.action-btn-download').click(),
    ]);

    const dir = mkdtempSync(path.join(tmpdir(), 'cloudshare-e2e-download-'));
    const savePath = path.join(dir, download.suggestedFilename());
    await download.saveAs(savePath);
    return savePath;
}

/* --- Share modal helpers --- */

export async function openShareModalFor(page: Page, filename: string): Promise<void> {
    await fileRow(page, filename).locator('.action-btn-share').click();
    await expect(page.locator('#share-modal')).not.toHaveClass(/hidden/);
}

/**
 * Submits the internal share form and captures the created shareId by
 * intercepting the POST response (rather than scraping the DOM, which
 * doesn't expose it).
 */
export async function shareInternally(page: Page, targetUsernameOrEmail: string, permission: 'READ' | 'WRITE' = 'READ'): Promise<string> {
    await page.locator('#tab-internal-btn').click();
    await page.locator('#share-internal-target').fill(targetUsernameOrEmail);
    await page.locator('#share-internal-permission').selectOption(permission);

    const [response] = await Promise.all([
        page.waitForResponse((res) => res.url().includes('/api/v1/shares/internal') && res.request().method() === 'POST'),
        page.locator('#share-internal-form button[type="submit"]').click(),
    ]);

    const body = await response.json();
    return body.data.shareId as string;
}

export interface PublicLinkOptions {
    expiresInSeconds?: 3600 | 86400 | 604800;
    password?: string;
    downloadLimit?: number;
}

/**
 * Submits the public link form and captures the created shareCode by
 * intercepting the POST response.
 */
export async function createPublicLink(page: Page, options: PublicLinkOptions = {}): Promise<string> {
    await page.locator('#tab-public-btn').click();

    if (options.expiresInSeconds) {
        await page.locator('#share-public-expiry').selectOption(String(options.expiresInSeconds));
    }
    if (options.password) {
        await page.locator('#share-public-password').fill(options.password);
    }
    if (options.downloadLimit) {
        await page.locator('#share-public-limit').fill(String(options.downloadLimit));
    }

    const [response] = await Promise.all([
        page.waitForResponse((res) => res.url().includes('/api/v1/shares/link') && res.request().method() === 'POST'),
        page.locator('#share-public-form button[type="submit"]').click(),
    ]);

    const body = await response.json();
    return body.data.shareCode as string;
}

/* --- Public (unauthenticated) share gateway helpers --- */

export async function openPublicShareLink(context: BrowserContext, shareCode: string): Promise<Page> {
    const page = await context.newPage();
    await page.goto(`/#share/${shareCode}`);
    await expect(page.locator('#public-share-gateway')).not.toHaveClass(/hidden/);
    return page;
}

/* --- Auth token / API helpers (for setup & teardown that isn't the thing under test) --- */

/**
 * Logs in via the raw API (no UI) to obtain a bearer token, for use with
 * Playwright's APIRequestContext. Used for state setup/teardown - e.g.
 * revocation calls - where driving the UI adds no test value.
 */
export async function apiLogin(
    request: import('@playwright/test').APIRequestContext,
    baseURL: string,
    user: TestUser,
    mfaCode?: string,
): Promise<string> {
    const payload: Record<string, string> = {
        usernameOrEmail: user.username,
        password: user.password,
    };
    if (mfaCode) {
        payload.mfaCode = mfaCode;
    }

    const res = await request.post(`${baseURL}/api/v1/auth/login`, { data: payload });
    expect(res.ok(), `API login failed for ${user.username}: ${res.status()} ${await res.text()}`).toBeTruthy();
    const body = await res.json();
    return body.data.accessToken as string;
}

/**
 * Waits until the current 30-second TOTP time-step rotates to avoid anti-replay rejection.
 */
export async function waitForTotpRotation(): Promise<void> {
    const currentStep = Math.floor(Date.now() / 1000 / 30);
    while (Math.floor(Date.now() / 1000 / 30) === currentStep) {
        await new Promise(resolve => setTimeout(resolve, 1000));
    }
}