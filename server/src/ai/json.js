/**
 * Models sometimes wrap JSON in prose or a code fence. Returns the first JSON object found in [text], or null.
 * Never throws; callers treat null as "the model did not follow the format".
 */
export function extractJsonObject(text) {
  if (typeof text !== 'string') return null;
  const cleaned = text.replace(/```(?:json)?/gi, '');
  const start = cleaned.indexOf('{');
  if (start < 0) return null;
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < cleaned.length; i += 1) {
    const ch = cleaned[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) {
        try {
          const value = JSON.parse(cleaned.slice(start, i + 1));
          return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
        } catch {
          return null;
        }
      }
    }
  }
  return null;
}
