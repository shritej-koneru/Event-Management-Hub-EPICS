import { ApiError } from '../utils/errors.js';

/**
 * Cleans free text before it is sent to the model: control characters are removed, runs of blank lines collapsed,
 * and a text over the limit is REJECTED (400) rather than silently cut, so a user never gets an answer about text
 * they did not send. Returns the cleaned text.
 */
export function cleanInput(value, maxChars, field = 'text') {
  if (typeof value !== 'string') {
    throw ApiError.badRequest(`${field} must be text`, 'INVALID_REQUEST');
  }
  // eslint-disable-next-line no-control-regex -- stripping control characters is the point
  const cleaned = value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').replace(/\n{3,}/g, '\n\n').trim();
  if (!cleaned) throw ApiError.badRequest(`${field} is required`, 'INVALID_REQUEST');
  if (cleaned.length > maxChars) {
    throw ApiError.badRequest(`${field} is too long (max ${maxChars} characters)`, 'INPUT_TOO_LONG');
  }
  return cleaned;
}
