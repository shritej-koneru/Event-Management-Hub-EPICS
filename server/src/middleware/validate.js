import { ApiError } from '../utils/errors.js';

/**
 * Validates and coerces a request segment with a zod schema.
 *
 *   router.post('/', validate({ body: createEventSchema }), controller.create)
 *
 * The parsed value replaces the raw input, so handlers always work with
 * trimmed, typed data and can never read an unvalidated field.
 */
export function validate(schemas) {
  return (req, res, next) => {
    try {
      if (schemas.body) req.body = schemas.body.parse(req.body ?? {});
      if (schemas.query) {
        // req.query is a getter in Express 5; assign defensively.
        Object.defineProperty(req, 'validatedQuery', {
          value: schemas.query.parse(req.query ?? {}),
          configurable: true,
          writable: true
        });
      }
      if (schemas.params) req.params = schemas.params.parse(req.params ?? {});
      return next();
    } catch (err) {
      return next(ApiError.badRequest(formatZodError(err), 'VALIDATION_ERROR', err.issues));
    }
  };
}

function formatZodError(err) {
  if (err?.issues?.length) {
    const detail = err.issues
      .map((issue) => {
        const field = (issue.path || []).join('.') || 'request';
        return `${field}: ${issue.message}`;
      })
      .join('; ');
    return `Invalid request — ${detail}`;
  }
  return 'Invalid request body';
}

/** Reads the validated query object, falling back to the raw one. */
export function validatedQuery(req) {
  return req.validatedQuery ?? req.query ?? {};
}