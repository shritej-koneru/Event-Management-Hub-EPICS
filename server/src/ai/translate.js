import { config } from '../config/index.js';
import { ApiError } from '../utils/errors.js';
import { chatCompletion } from './llmClient.js';

/**
 * Translation with nvidia/riva-translate-4b-instruct-v2.
 *
 * Verified against the live API (2026-11-11): the model IGNORES system prompts (it answered Czech for every request)
 * and only follows a question-style USER message ("What is the Hindi translation of the sentence: ..."). It also
 * produced the wrong language for Telugu, Tamil, Bengali, Marathi, Gujarati, Urdu, Kannada, Malayalam and Punjabi, so
 * only the languages below (all checked) are offered. Where the output must be in a non-Latin script that is checked
 * too, so a wrong-language answer is rejected instead of shown.
 */
export const TRANSLATE_LANGUAGES = Object.freeze({
  hi: { name: 'Hindi', script: /[ऀ-ॿ]/ },
  es: { name: 'Spanish' },
  fr: { name: 'French' },
  de: { name: 'German' },
  it: { name: 'Italian' },
  pt: { name: 'Portuguese' },
  ar: { name: 'Arabic', script: /[؀-ۿ]/ },
  zh: { name: 'Chinese', script: /[一-鿿]/ },
  ja: { name: 'Japanese', script: /[぀-ヿ一-鿿]/ },
  ko: { name: 'Korean', script: /[가-힯]/ },
  ru: { name: 'Russian', script: /[Ѐ-ӿ]/ }
});

/** The languages offered to users: `[{ code, name }]`. */
export function supportedLanguages() {
  return Object.entries(TRANSLATE_LANGUAGES).map(([code, { name }]) => ({ code, name }));
}

/**
 * Translates [text] into the language [code]. The original is never touched; the caller returns both.
 * @returns {Promise<{ text: string, language: string, model: string }>}
 */
export async function translateText(text, code, deps = {}) {
  const language = TRANSLATE_LANGUAGES[code];
  if (!language) {
    throw ApiError.badRequest(
      `Unsupported language. Choose one of: ${supportedLanguages().map((l) => l.code).join(', ')}`,
      'UNSUPPORTED_LANGUAGE'
    );
  }
  const aiConfig = deps.aiConfig ?? config.ai;
  const result = await chatCompletion(
    {
      user: `What is the ${language.name} translation of the sentence: ${text}`,
      model: aiConfig.translateModel,
      maxTokens: Math.min(aiConfig.maxOutputTokens, Math.max(64, text.length * 3)),
      temperature: 0
    },
    deps
  );
  const out = result.text.replace(/^["“”]+|["“”]+$/g, '').trim();
  if (language.script && !language.script.test(out)) {
    console.warn(`[ai] translation to ${code} came back in the wrong script; rejected`);
    throw ApiError.badGateway('The translation could not be produced reliably', 'AI_BAD_RESPONSE');
  }
  return { text: out, language: code, model: result.model };
}
