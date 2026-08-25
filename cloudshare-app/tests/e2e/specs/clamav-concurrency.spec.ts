import { test, expect } from '@playwright/test';
import { makeTestUser, registerAndLogin, waitForTotpRotation, apiLogin } from '../helpers';
import { generateTotp } from '../totp';
import { promoteUserToAdmin } from '../db';

test.describe('ClamAV Concurrency Cap E2E Test', () => {
    let adminUser: any;
    let adminToken: string = '';
    let mfaSecret: string = '';
    let currentStepUpToken: string = '';

    test.beforeEach(async ({ request, baseURL }) => {
        adminUser = makeTestUser('clamadm');
        
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
        // Guaranteed restoration of the ClamAV limit to 8
        if (adminToken && currentStepUpToken) {
            const res = await request.post(`${baseURL}/api/v1/admin/clamav/limit?limit=8`, {
                headers: {
                    'Authorization': `Bearer ${adminToken}`,
                    'X-StepUp-Token': currentStepUpToken
                }
            });
            if (res.ok()) {
                console.log('ClamAV scan concurrency limit successfully restored to 8.');
            } else {
                console.warn(`Failed to restore ClamAV limit: ${res.status()} ${await res.text()}`);
            }
        }
    });

    test('rejects uploads exceeding ClamAV scan capacity under concurrent pressure', async ({ page, request, baseURL }) => {
        // Set the limit to 1 dynamically
        const limitRes = await request.post(`${baseURL}/api/v1/admin/clamav/limit?limit=1`, {
            headers: {
                'Authorization': `Bearer ${adminToken}`,
                'X-StepUp-Token': currentStepUpToken
            }
        });
        expect(limitRes.ok()).toBeTruthy();
        
        // Save the rotated/successor token for the restore call in afterEach
        currentStepUpToken = limitRes.headers()['x-stepup-token'];
        expect(currentStepUpToken).toBeDefined();

        // Register and login a separate upload user (sequential flow)
        const uploadUser = makeTestUser('clamuploader');
        await registerAndLogin(page, uploadUser);

        // Execute concurrent uploads in the page context of the logged-in uploader user
        const statuses = await page.evaluate(async () => {
            const token = (globalThis as any).api.getAccessToken();
            
            // Create a small file blob for upload
            const blob = new Blob(['concurrency test content'], { type: 'text/plain' });
            
            const uploadFilePromise = (index: number) => {
                const formData = new FormData();
                formData.append('file', blob, `clam-concur-${index}.txt`);
                return fetch('/api/v1/files/upload', {
                    method: 'POST',
                    headers: { 'Authorization': `Bearer ${token}` },
                    body: formData
                });
            };

            // Fire 10 uploads concurrently to guarantee concurrency cap is exceeded
            const responses = await Promise.all(
                Array.from({ length: 10 }).map((_, i) => uploadFilePromise(i))
            );
            return responses.map(r => r.status);
        });

        // Verify that we successfully uploaded at least one file (201) and rejected at least one file (503)
        expect(statuses).toContain(201);
        expect(statuses).toContain(503);
    });
});
