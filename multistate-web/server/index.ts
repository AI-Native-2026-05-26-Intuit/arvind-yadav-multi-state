import { serve } from '@hono/node-server';
import { Hono } from 'hono';
import { chat } from './api/chat';

const app = new Hono();

app.route('/api', chat);

const port = Number(process.env.PORT ?? 3001);

serve({ fetch: app.fetch, port }, (info) => {
  console.log(`chat proxy listening on http://localhost:${info.port}`);
});
