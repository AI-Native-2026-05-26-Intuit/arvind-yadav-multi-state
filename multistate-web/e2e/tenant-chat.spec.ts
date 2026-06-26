import { test, expect, type Route } from '@playwright/test';

// W4 D5 Task 3 happy path. Apollo and the AI SDK chat proxy are stubbed
// at the network layer so the test does not depend on the (currently
// non-existent) Spring AI backend. The Vite dev server still boots and
// serves the real client bundle — only the network calls leaving the
// browser are intercepted.

const TENANT_ID = 'ten_synth_a1b2';

function buildAiStream(): string {
  // AI SDK v4 data-stream protocol:
  //   0:"<delta>"        text token delta
  //   9:{toolCallId,toolName,args}     tool call
  //   a:{toolCallId,result}            tool result
  //   d:{finishReason,usage}           stream finish
  // Frames are LF-terminated; the SDK does not require a [DONE] sentinel.
  return [
    `0:${JSON.stringify('stub ')}`,
    `0:${JSON.stringify('tenant ')}`,
    `0:${JSON.stringify('reply.')}`,
    `9:${JSON.stringify({
      toolCallId: 'call_synth_1',
      toolName:   'lookupTenant',
      args:       { id: TENANT_ID },
    })}`,
    `a:${JSON.stringify({
      toolCallId: 'call_synth_1',
      result:     { id: TENANT_ID, primaryState: 'CA' },
    })}`,
    `d:${JSON.stringify({
      finishReason: 'stop',
      usage:        { promptTokens: 1, completionTokens: 3 },
    })}`,
    '',
  ].join('\n');
}

async function fulfillGraphQL(route: Route): Promise<void> {
  const body = route.request().postDataJSON() as { operationName?: string } | null;
  if (body?.operationName === 'LatestTenants') {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          latestTenants: [
            { __typename: 'Tenant', id: TENANT_ID,        name: 'Stub Tenant 01', updatedAt: '2025-01-01T00:00:00Z' },
            { __typename: 'Tenant', id: 'ten_synth_c3d4', name: 'Stub Tenant 02', updatedAt: '2025-01-02T00:00:00Z' },
          ],
        },
      }),
    });
    return;
  }
  await route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ data: null }),
  });
}

test.describe('MultiState W4 capstone happy-path', () => {
  test('engineer chats with the assistant and history survives reload', async ({ page }) => {
    // Apollo
    await page.route('**/graphql', fulfillGraphQL);
    // The Vercel AI SDK posts to /api/chat (proxied to Hono on :3001 in real
    // life). Intercepting here keeps the test independent of the proxy.
    await page.route('**/api/chat', async (route) => {
      await route.fulfill({
        status: 200,
        headers: {
          'Content-Type':            'text/event-stream',
          'Cache-Control':           'no-cache, no-transform',
          'X-Vercel-AI-Data-Stream': 'v1',
        },
        body: buildAiStream(),
      });
    });

    await page.goto('/tenants');
    await expect(
      page.getByRole('heading', { name: /tenants/i }),
    ).toBeVisible();

    // Open the first tenant row by accessible name.
    await page.getByRole('row', { name: /Stub Tenant 01/i }).click();
    await expect(page).toHaveURL(new RegExp(`/tenants/${TENANT_ID}`));

    // Drive the W4 D4 streamed chat panel.
    await page.getByRole('textbox', { name: /chat-input/i }).fill('hello');
    await page.getByRole('button', { name: /send/i }).click();

    // Web-first assertion: tokens stream in; do NOT use waitForTimeout.
    await expect(page.getByRole('log')).toContainText(/stub/i);

    // Tool call renders inline beneath the assistant message.
    await expect(page.getByLabel('tool-call')).toBeVisible();

    // Reload; the persisted assistant message survives.
    await page.reload();
    await expect(page.getByRole('log')).toContainText(/stub/i);
  });
});
