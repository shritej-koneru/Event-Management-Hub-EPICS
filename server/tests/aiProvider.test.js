import express from 'express';
import supertest from 'supertest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './helpers/testContext.js';
import { AI_DEFAULTS, config, resolveAiConfig } from '../src/config/index.js';
import { chatCompletion } from '../src/ai/llmClient.js';
import { supportedLanguages, translateText } from '../src/ai/translate.js';
import { cleanInput } from '../src/ai/inputLimits.js';
import { buildAiRateLimit } from '../src/middleware/rateLimit.js';
import { ApiError } from '../src/utils/errors.js';

// The AI foundation: configuration, the provider client, translation guard, input limits, the rate limiter and the
// public status endpoint. The provider is always a fake `fetch`: no test calls the real API or needs a key.

const KEY = 'nvapi-TEST-ONLY-not-a-real-key-0123456789';
const aiConfig = (over = {}) => ({ ...resolveAiConfig({ NVIDIA_API_KEY: KEY }), ...over });

const reply = (content, init = {}) =>
  new Response(JSON.stringify({ choices: [{ message: { content } }], usage: { prompt_tokens: 7, completion_tokens: 3 } }), {
    status: 200,
    headers: { 'x-request-id': 'req-1' },
    ...init
  });

const failure = async (promise) => promise.then(() => null, (err) => err);

afterEach(() => vi.restoreAllMocks());

describe('resolveAiConfig', () => {
  it('is disabled without a key and uses the documented defaults', () => {
    const c = resolveAiConfig({});
    expect(c.enabled).toBe(false);
    expect(c.chatModel).toBe('nvidia/nemotron-3.5-bolt-e4b');
    expect(c.translateModel).toBe('nvidia/riva-translate-4b-instruct-v2');
    expect(c.baseUrl).toBe(AI_DEFAULTS.baseUrl);
    expect(c.timeoutMs).toBe(15_000);
  });

  it('is enabled by a key (trimmed) and switched off by AI_ENABLED=false', () => {
    expect(resolveAiConfig({ NVIDIA_API_KEY: `  ${KEY}\n` })).toMatchObject({ enabled: true, apiKey: KEY });
    expect(resolveAiConfig({ NVIDIA_API_KEY: KEY, AI_ENABLED: 'false' }).enabled).toBe(false);
    expect(resolveAiConfig({ NVIDIA_API_KEY: KEY, AI_ENABLED: 'no' }).enabled).toBe(false);
  });

  it('lets the model ids be set from the environment', () => {
    const c = resolveAiConfig({ NVIDIA_API_KEY: KEY, NVIDIA_CHAT_MODEL: 'x/chat', NVIDIA_TRANSLATE_MODEL: 'x/tr' });
    expect(c).toMatchObject({ chatModel: 'x/chat', translateModel: 'x/tr' });
  });

  it('only accepts an https base URL (http only for localhost) and strips a trailing slash', () => {
    expect(resolveAiConfig({ NVIDIA_BASE_URL: 'https://example.test/v1/' }).baseUrl).toBe('https://example.test/v1');
    expect(resolveAiConfig({ NVIDIA_BASE_URL: 'http://localhost:9999/v1' }).baseUrl).toBe('http://localhost:9999/v1');
    expect(resolveAiConfig({ NVIDIA_BASE_URL: 'http://evil.test/v1' }).baseUrl).toBe(AI_DEFAULTS.baseUrl);
    expect(resolveAiConfig({ NVIDIA_BASE_URL: 'not a url' }).baseUrl).toBe(AI_DEFAULTS.baseUrl);
  });

  it('ignores numbers that are out of range or not numbers', () => {
    const c = resolveAiConfig({ AI_TIMEOUT_MS: '5', AI_MAX_INPUT_CHARS: 'abc', AI_RATE_PER_MINUTE: '99999', AI_MAX_OUTPUT_TOKENS: '256' });
    expect(c).toMatchObject({ timeoutMs: 15_000, maxInputChars: 2_000, ratePerMinute: 20, maxOutputTokens: 256 });
  });

  it('is not enabled in the running test server (no key is configured there)', () => {
    expect(config.ai.enabled).toBe(false);
  });
});

describe('chatCompletion', () => {
  it('sends the key only in the Authorization header, with a capped output size, and returns the text', async () => {
    const fetchImpl = vi.fn(async () => reply('  Hello there  '));

    const out = await chatCompletion({ system: 'Be brief.', user: 'Hi', maxTokens: 9999 }, { aiConfig: aiConfig(), fetchImpl });

    expect(out).toMatchObject({ text: 'Hello there', model: 'nvidia/nemotron-3.5-bolt-e4b', usage: { promptTokens: 7, completionTokens: 3 } });
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe('https://integrate.api.nvidia.com/v1/chat/completions');
    expect(init.headers.Authorization).toBe(`Bearer ${KEY}`);
    const body = JSON.parse(init.body);
    expect(body.model).toBe('nvidia/nemotron-3.5-bolt-e4b');
    expect(body.messages).toEqual([{ role: 'system', content: 'Be brief.' }, { role: 'user', content: 'Hi' }]);
    expect(body.max_tokens).toBe(400); // capped to AI_MAX_OUTPUT_TOKENS
    expect(init.body).not.toContain(KEY);
  });

  it('answers 503 AI_DISABLED without calling the provider when no key is configured', async () => {
    const fetchImpl = vi.fn();
    const err = await failure(chatCompletion({ user: 'Hi' }, { aiConfig: resolveAiConfig({}), fetchImpl }));

    expect(err).toBeInstanceOf(ApiError);
    expect([err.status, err.code]).toEqual([503, 'AI_DISABLED']);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('turns a slow provider into 503 AI_TIMEOUT', async () => {
    const fetchImpl = (_url, { signal }) =>
      new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))));
    const err = await failure(chatCompletion({ user: 'Hi' }, { aiConfig: aiConfig({ timeoutMs: 20 }), fetchImpl }));

    expect([err.status, err.code]).toEqual([503, 'AI_TIMEOUT']);
  });

  it('turns a network failure into 503 AI_UNAVAILABLE', async () => {
    const err = await failure(chatCompletion({ user: 'Hi' }, { aiConfig: aiConfig(), fetchImpl: async () => { throw new TypeError('fetch failed'); } }));
    expect([err.status, err.code]).toEqual([503, 'AI_UNAVAILABLE']);
  });

  it('maps provider statuses safely: 401/403/500 -> 503, 429 -> 429, never exposing the provider body', async () => {
    for (const status of [401, 403, 500, 502]) {
      const fetchImpl = async () => new Response(`secret provider detail ${KEY}`, { status });
      const err = await failure(chatCompletion({ user: 'Hi' }, { aiConfig: aiConfig(), fetchImpl }));
      expect([err.status, err.code]).toEqual([503, 'AI_UNAVAILABLE']);
      expect(JSON.stringify({ message: err.message, code: err.code })).not.toMatch(/secret provider detail|nvapi/);
    }
    const limited = await failure(chatCompletion({ user: 'Hi' }, { aiConfig: aiConfig(), fetchImpl: async () => new Response('slow down', { status: 429 }) }));
    expect([limited.status, limited.code]).toEqual([429, 'AI_RATE_LIMITED']);
  });

  it('rejects invalid JSON and empty answers as 502 AI_BAD_RESPONSE', async () => {
    const badJson = await failure(chatCompletion({ user: 'Hi' }, { aiConfig: aiConfig(), fetchImpl: async () => new Response('<html>', { status: 200 }) }));
    expect([badJson.status, badJson.code]).toEqual([502, 'AI_BAD_RESPONSE']);

    for (const content of ['', '   ', null]) {
      const empty = await failure(chatCompletion({ user: 'Hi' }, { aiConfig: aiConfig(), fetchImpl: async () => reply(content) }));
      expect([empty.status, empty.code]).toEqual([502, 'AI_BAD_RESPONSE']);
    }
  });

  it('never writes the key or the user text to the log, only what failed', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await failure(chatCompletion({ user: 'my private question' }, { aiConfig: aiConfig(), fetchImpl: async () => new Response(`${KEY} private`, { status: 401 }) }));

    const logged = warn.mock.calls.flat().join(' ');
    expect(logged).toContain('status=401');
    expect(logged).not.toContain(KEY);
    expect(logged).not.toContain('my private question');
  });
});

describe('translateText', () => {
  it('uses a question-style user message with the translation model (the model ignores system prompts)', async () => {
    const fetchImpl = vi.fn(async () => reply('"पंजीकरण शुक्रवार को समाप्त हो जाता है।"'));

    const out = await translateText('Registration closes on Friday.', 'hi', { aiConfig: aiConfig(), fetchImpl });

    expect(out).toEqual({ text: 'पंजीकरण शुक्रवार को समाप्त हो जाता है।', language: 'hi', model: 'nvidia/riva-translate-4b-instruct-v2' });
    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(body.model).toBe('nvidia/riva-translate-4b-instruct-v2');
    expect(body.messages).toEqual([{ role: 'user', content: 'What is the Hindi translation of the sentence: Registration closes on Friday.' }]);
    expect(body.temperature).toBe(0);
  });

  it('rejects an unsupported language (400) without calling the provider', async () => {
    const fetchImpl = vi.fn();
    for (const code of ['te', 'ta', 'xx', '', undefined]) {
      const err = await failure(translateText('Hello', code, { aiConfig: aiConfig(), fetchImpl }));
      expect([err.status, err.code]).toEqual([400, 'UNSUPPORTED_LANGUAGE']);
    }
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('rejects an answer in the wrong script (the model once returned Czech for Hindi)', async () => {
    const err = await failure(translateText('Hello', 'hi', { aiConfig: aiConfig(), fetchImpl: async () => reply('Tech Carnival začne zítra v hlavní hale.') }));
    expect([err.status, err.code]).toEqual([502, 'AI_BAD_RESPONSE']);
  });

  it('accepts Latin-script languages without a script check', async () => {
    const out = await translateText('Hello', 'es', { aiConfig: aiConfig(), fetchImpl: async () => reply('Hola') });
    expect(out.text).toBe('Hola');
  });

  it('only offers languages that were verified', () => {
    const codes = supportedLanguages().map((l) => l.code);
    expect(codes).toEqual(expect.arrayContaining(['hi', 'es', 'fr', 'de', 'ar', 'zh', 'ja', 'ko', 'ru', 'it', 'pt']));
    for (const unverified of ['te', 'ta', 'bn', 'mr', 'gu', 'ur', 'kn', 'ml', 'pa']) expect(codes).not.toContain(unverified);
  });
});

describe('cleanInput', () => {
  it('strips control characters and trims', () => {
    expect(cleanInput('  hi\u0000 there\u0007 \n\n\n\nnext  ', 100)).toBe('hi there \n\nnext');
  });

  it('rejects empty, non-text and over-long input instead of truncating', () => {
    expect(failureSync(() => cleanInput('   ', 10)).code).toBe('INVALID_REQUEST');
    expect(failureSync(() => cleanInput(42, 10)).code).toBe('INVALID_REQUEST');
    const tooLong = failureSync(() => cleanInput('x'.repeat(11), 10, 'message'));
    expect([tooLong.status, tooLong.code]).toEqual([400, 'INPUT_TOO_LONG']);
    expect(cleanInput('x'.repeat(10), 10)).toHaveLength(10);
  });
});

function failureSync(fn) {
  try {
    fn();
  } catch (err) {
    return err;
  }
  return null;
}

describe('AI rate limiter', () => {
  const app = (max) => {
    const a = express();
    a.use((req, _res, next) => {
      const uid = req.get('x-test-user');
      if (uid) req.auth = { uid };
      next();
    });
    a.get('/x', buildAiRateLimit(max), (_req, res) => res.json({ ok: true }));
    return supertest(a);
  };

  it('answers 429 AI_RATE_LIMITED after the limit', async () => {
    const request = app(2);
    expect((await request.get('/x')).status).toBe(200);
    expect((await request.get('/x')).status).toBe(200);
    const limited = await request.get('/x');
    expect(limited.status).toBe(429);
    expect(limited.body.code).toBe('AI_RATE_LIMITED');
  });

  it('counts each signed-in user separately', async () => {
    const request = app(1);
    expect((await request.get('/x').set('x-test-user', 'u1')).status).toBe(200);
    expect((await request.get('/x').set('x-test-user', 'u2')).status).toBe(200);
    expect((await request.get('/x').set('x-test-user', 'u1')).status).toBe(429);
  });
});

describe('GET /api/ai/status', () => {
  it('is public and says AI is off when no key is configured', async () => {
    const res = await api().get('/api/ai/status');

    expect(res.status).toBe(200);
    expect(res.body.enabled).toBe(false);
    expect(res.body.languages.map((l) => l.code)).toContain('hi');
    expect(res.body.maxInputChars).toBe(config.ai.maxInputChars);
  });

  it('reports enabled when configured, and never returns the key or provider details', async () => {
    const before = { ...config.ai };
    Object.assign(config.ai, resolveAiConfig({ NVIDIA_API_KEY: KEY }));
    try {
      const res = await api().get('/api/ai/status');
      expect(res.body.enabled).toBe(true);
      const text = JSON.stringify(res.body);
      expect(text).not.toContain(KEY);
      expect(text).not.toMatch(/apiKey|nvapi|baseUrl|nemotron|riva/);
    } finally {
      Object.assign(config.ai, before);
    }
  });

  it('leaves the existing AI endpoints unchanged', async () => {
    const res = await api().post('/api/ai/search').send({ query: 'music' });
    expect(res.status).toBe(200);
    expect(Array.isArray(res.body.results)).toBe(true);
  });
});
