import { chromium, type FullConfig } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';

const STORAGE_STATE_PATH = 'e2e/.auth/user.json';

export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use.baseURL as string | undefined;
  if (!baseURL) throw new Error('global-setup: baseURL missing on chromium project');

  const browser = await chromium.launch();
  const page = await browser.newPage();

  try {
    await page.goto(`${baseURL}/login`);
    await page.getByLabel(/email/i).fill('engineer@uptimecrew.example.internal');
    await page.getByLabel(/password/i).fill('synthetic-test-pwd');
    await page.getByRole('button', { name: /sign in/i }).click();
    // Wait for the protected layout to render before snapshotting the
    // localStorage-backed JWT.
    await page.waitForURL(/\/tenants/);

    await mkdir(dirname(STORAGE_STATE_PATH), { recursive: true });
    await page.context().storageState({ path: STORAGE_STATE_PATH });
  } finally {
    await browser.close();
  }
}
