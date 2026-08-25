
import * as fs from 'fs';
import * as crypto from 'crypto';
import * as os from 'os';
import * as path from 'path';

import { test, expect } from '@playwright/test';
import {
    makeTestUser,
    registerAndLogin,
    expectToast,
    createTempFile,
    uploadFileViaUI,
    openShareModalFor,
    createPublicLink,
    openPublicShareLink,
    apiLogin,
    waitForTotpRotation
} from '../helpers';
import { generateTotp } from '../totp';
import { promoteUserToAdmin } from '../db';

test.describe('Download Concurrency Cap E2E Test', () => {
    let adminUser: any;
    let adminToken: string = '';
    let mfaSecret: string = '';
    let currentStepUpToken: string = '';

    test.beforeEach(async ({ request, baseURL }) => {
        adminUser = makeTestUser('downadm');
        
        // 1. Create and register user
        const signupRes = await request.post(`${baseURL}/api/v1/auth/register`, { data: adminUser });
        expect(signupRes.ok()).toBeTruthy();

        // 2. Promote to Admin directly in Database
        promoteUserToAdmin(adminUser.username);

        // 3. Login to get token
        adminToken = await apiLogin(request, baseURL!, adminUser);

        // 4. Enroll in MFA
        const setupRes = await request.post(`${baseURL}/api/v1/auth/mfa/setup`, {
            headers: { 'Authorization': `Bearer ${adminToken}` }
        });
        expect(setupRes.ok()).toBeTruthy();
        const setupBody = await setupRes.json();
        mfaSecret = setupBody.data.secret;

        const verifyRes = await request.post(`${baseURL}/api/v1/auth/mfa/verify`, {
            headers: { 'Authorization': `Bearer ${adminToken}` },
            data: { code: generateTotp(mfaSecret) }
        });
        expect(verifyRes.ok()).toBeTruthy();

        // 5. Generate a step-up token
        await waitForTotpRotation();
        const stepUpRes = await request.post(`${baseURL}/api/v1/auth/mfa/step-up`, {
            headers: { 'Authorization': `Bearer ${adminToken}` },
            data: { code: generateTotp(mfaSecret) }
        });
        expect(stepUpRes.ok()).toBeTruthy();
        const stepUpBody = await stepUpRes.json();
        currentStepUpToken = stepUpBody.data.stepUpToken;
    });

    test.afterEach(async ({ request, baseURL }) => {
        // Guaranteed restoration of the download concurrency limit to 20
        if (adminToken && currentStepUpToken) {
            const res = await request.post(`${baseURL}/api/v1/admin/downloads/limit?limit=20`, {
                headers: {
                    'Authorization': `Bearer ${adminToken}`,
                    'X-StepUp-Token': currentStepUpToken
                }
            });
            if (res.ok()) {
                console.log('Download concurrency limit successfully restored to 20.');
            } else {
                console.warn(`Failed to restore download limit: ${res.status()} ${await res.text()}`);
            }
        }
    });

    test('rejects downloads exceeding concurrency capacity under concurrent pressure', async ({ page, request, baseURL, browser }) => {
        // Set the limit to 1 dynamically
        const limitRes = await request.post(`${baseURL}/api/v1/admin/downloads/limit?limit=1`, {
            headers: {
                'Authorization': `Bearer ${adminToken}`,
                'X-StepUp-Token': currentStepUpToken
            }
        });
        expect(limitRes.ok()).toBeTruthy();
        
        // Save the rotated/successor token for the restore call in afterEach
        currentStepUpToken = limitRes.headers()['x-stepup-token'];
        expect(currentStepUpToken).toBeDefined();

        // Register and login a separate user to upload the test file
        const uploader = makeTestUser('downuploader');
        await registerAndLogin(page, uploader);

        // Generate a large temp file (8MB) to force decryption latency and trigger concurrency block
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cloudshare-e2e-large-'));
        const name = `down-concur-large-${crypto.randomBytes(4).toString('hex')}.txt`;
        const filePath = path.join(dir, name);
        fs.writeFileSync(filePath, crypto.randomBytes(8 * 1024 * 1024));
        const fixture = { path: filePath, name };

        // Upload the file and capture its fileId from the API response
        const [uploadResponse] = await Promise.all([
            page.waitForResponse((res) => res.url().includes('/api/v1/files/upload') && res.request().method() === 'POST'),
            uploadFileViaUI(page, fixture.path)
        ]);
        const uploadBody = await uploadResponse.json();
        const fileId = uploadBody.data.id;
        expect(fileId).toBeTruthy();

        // Execute concurrent downloads in the page context of the logged-in uploader user
        const statuses = await page.evaluate(async (fId) => {
            const token = (globalThis as any).api.getAccessToken();
            const responses = await Promise.all(
                Array.from({ length: 10 }).map((_, i) =>
                    fetch(`/api/v1/files/${fId}/download?cb=${i}`, {
                        headers: { 'Authorization': `Bearer ${token}` }
                    })
                )
            );
            return responses.map(r => r.status);
        }, fileId);

        expect(statuses).toContain(200);
        expect(statuses).toContain(503);
    });
});
