/**
 * Centralized application error. Every thrown/forwarded error carries an HTTP
 * status and a stable machine-readable `code` that matches the shared contract
 * (contract/API-CONTRACT.md §7).
 */
export class ApiError extends Error {
  constructor(status, message, code = 'ERROR', details = undefined) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    if (details !== undefined) this.details = details;
    Error.captureStackTrace?.(this, ApiError);
  }

  static badRequest(message, code = 'VALIDATION_ERROR', details) {
    return new ApiError(400, message, code, details);
  }

  static unauthorized(message = 'Authentication required', code = 'AUTH_REQUIRED') {
    return new ApiError(401, message, code);
  }

  static forbidden(message = 'Forbidden', code = 'FORBIDDEN') {
    return new ApiError(403, message, code);
  }

  static notFound(message = 'Resource not found', code = 'NOT_FOUND') {
    return new ApiError(404, message, code);
  }

  static conflict(message, code = 'CONFLICT') {
    return new ApiError(409, message, code);
  }

  static internal(message = 'Internal server error', code = 'INTERNAL') {
    return new ApiError(500, message, code);
  }
}

/**
 * Wraps thrown values so the error handler always has an ApiError to work with.
 *
 * Database messages are never forwarded to the client: `err.detail` embeds
 * constraint names and the conflicting values, and driver messages can describe
 * internal schema. The real error is logged by the error handler instead.
 */
export function toApiError(err) {
  if (err instanceof ApiError) return err;
  // PostgreSQL unique_violation - surfaced as a 409 rather than a 500.
  if (err?.code === '23505') {
    return ApiError.conflict('Resource already exists', 'DUPLICATE');
  }
  // body-parser failures are client faults, not server faults. Without this they
  // would be reported as 500 INTERNAL and pollute server error monitoring.
  if (err?.type === 'entity.parse.failed') {
    return ApiError.badRequest('Malformed JSON body', 'INVALID_REQUEST');
  }
  if (err?.type === 'entity.too.large') {
    return ApiError.badRequest('Request body too large', 'PAYLOAD_TOO_LARGE');
  }
  // Any other library error that already declares a 4xx status stays 4xx.
  const declared = err?.status ?? err?.statusCode;
  if (Number.isInteger(declared) && declared >= 400 && declared < 500) {
    return ApiError.badRequest('Invalid request', 'INVALID_REQUEST');
  }
  return ApiError.internal('Internal server error');
}