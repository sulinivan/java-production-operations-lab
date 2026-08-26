/**
 * Test-only database helper. Promotes a user to ROLE_ADMIN by executing SQL
 * directly against the Postgres container via `docker compose exec`.
 *
 * This exists solely because registration always defaults to ROLE_USER (by
 * design - see security.md) and there is intentionally no product API to
 * self-promote. admin.spec.ts is the only consumer of this helper.
 *
 * Requires the Docker Compose stack (from the repo root) to already be up,
 * with the Postgres service reachable via `docker compose exec`.
 */

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// tests/e2e/db.ts -> repo root is two levels up
const REPO_ROOT = path.resolve(__dirname, '..', '..');

const DB_SERVICE = process.env.E2E_DB_SERVICE || 'db';
const DB_NAME = process.env.E2E_DB_NAME || 'cloudshare';
const DB_USER = process.env.SPRING_DATASOURCE_USERNAME || 'cloudshare_user';

function escapeSqlLiteral(value: string): string {
    return value.replace(/'/g, "''");
}

/**
 * Executes a single SQL statement inside the Postgres container.
 * Uses execFileSync (not execSync/string interpolation into a shell) so the
 * SQL payload can't be re-interpreted by the shell, and `-T` disables TTY
 * allocation so it works headlessly in CI.
 */
function runSql(sql: string): void {
    execFileSync(
        'docker',
        ['compose', 'exec', '-T', DB_SERVICE, 'psql', '-U', DB_USER, '-d', DB_NAME, '-v', 'ON_ERROR_STOP=1', '-c', sql],
        { cwd: REPO_ROOT, stdio: 'pipe' },
    );
}

/**
 * Grants ROLE_ADMIN to an existing user, identified by username.
 * Idempotent - safe to call more than once for the same user.
 */
export function promoteUserToAdmin(username: string): void {
    const safeUsername = escapeSqlLiteral(username);
    const sql =
        `INSERT INTO user_roles (user_id, role_id) ` +
        `SELECT u.id, r.id FROM users u, roles r ` +
        `WHERE u.username = '${safeUsername}' AND r.name = 'ROLE_ADMIN' ` +
        `ON CONFLICT DO NOTHING;`;

    runSql(sql);
}