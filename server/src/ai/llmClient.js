import { config } from '../config/index.js';
import { ApiError } from '../utils/errors.js';

/**
 * The ONLY place the server talks to the AI provider (NVIDIA's OpenAI-compatible chat-completions API).
 *
 * Rules enforced here for every caller:
 *  - the API key is read from config (env) and sent only in the Authorization header; it is never logged, put in an
 *    error, or returned;
 *  - every request has a hard timeout and a capped output size;
 *  - provider problems become SAFE errors (503 AI_UNAVAILABLE / AI_TIMEOUT / AI_DISABLED, 429 AI_RATE_LIMITED,
 *    502 AI_BAD_RESPONSE) that never include the provider's response body, so callers can fall back to non-AI behaviour;
 *  - the caller supplies already-minimised text: nothing about users is added here.
 */

const disabled = () => ApiError.unavailable('AI features are not available on this server right now', 'AI_DISABLED');

/** Logs a provider problem without any secret or user text: what failed, the status, the provider request id. */
function logProviderProblem(what, status, requestId) {
  console.warn(`[ai] ${what}${status ? ` status=${status}` : ''}${requestId ? ` request-id=${requestId}` : ''}`);
}

/**
 * One chat completion.
 *
 * @param {{ system?: string, user: string, model?: string, maxTokens?: number, temperature?: number }} request
 * @param {{ aiConfig?: object, fetchImpl?: typeof fetch }} [deps] injectable for tests
 * @returns {Promise<{ text: string, model: string, usage: { promptTokens: number|null, completionTokens: number|null } }>}
 */
export async function chatCompletion(
  { system, user, model, maxTokens, temperature = 0.2 },
  { aiConfig = config.ai, fetchImpl = globalThis.fetch } = {}
) {
  if (!aiConfig.enabled) throw disabled();

  const usedModel = model || aiConfig.chatModel;
  const messages = [];
  if (system) messages.push({ role: 'system', content: system });
  messages.push({ role: 'user', content: user });

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), aiConfig.timeoutMs);
  let response;
  try {
    response = await fetchImpl(`${aiConfig.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${aiConfig.apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: usedModel,
        messages,
        max_tokens: Math.min(maxTokens ?? aiConfig.maxOutputTokens, aiConfig.maxOutputTokens),
        temperature
      }),
      signal: controller.signal
    });
  } catch (err) {
    if (err?.name === 'AbortError') {
      logProviderProblem('request timed out');
      throw ApiError.unavailable('The AI service took too long to answer', 'AI_TIMEOUT');
    }
    logProviderProblem('request failed (network)');
    throw ApiError.unavailable('The AI service is unreachable right now', 'AI_UNAVAILABLE');
  } finally {
    clearTimeout(timer);
  }

  const requestId = response.headers?.get?.('x-request-id') ?? undefined;
  if (response.status === 429) {
    logProviderProblem('provider rate limit', 429, requestId);
    throw ApiError.tooManyRequests('The AI service is busy. Try again in a moment.', 'AI_RATE_LIMITED');
  }
  if (!response.ok) {
    // 401/403 means OUR key or access is wrong: log it for the operator, but never tell a user why.
    logProviderProblem('provider error', response.status, requestId);
    throw ApiError.unavailable('The AI service is unavailable right now', 'AI_UNAVAILABLE');
  }

  let body;
  try {
    body = await response.json();
  } catch {
    logProviderProblem('invalid JSON from provider', response.status, requestId);
    throw ApiError.badGateway('The AI service returned an unusable answer', 'AI_BAD_RESPONSE');
  }
  const text = body?.choices?.[0]?.message?.content;
  if (typeof text !== 'string' || !text.trim()) {
    logProviderProblem('empty answer from provider', response.status, requestId);
    throw ApiError.badGateway('The AI service returned an empty answer', 'AI_BAD_RESPONSE');
  }
  return {
    text: text.trim(),
    model: usedModel,
    usage: {
      promptTokens: body?.usage?.prompt_tokens ?? null,
      completionTokens: body?.usage?.completion_tokens ?? null
    }
  };
}
