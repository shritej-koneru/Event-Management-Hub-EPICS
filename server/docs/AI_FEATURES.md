# AI features: setup and operations

Ten features, all additive. See `API_REFERENCE.md` ("AI helpers") for the endpoints.

## Configuration (server environment only)

| Variable | Default | Notes |
|---|---|---|
| `NVIDIA_API_KEY` | (unset) | **Set in the Render dashboard.** Never commit it, never put it in the apps. Unset = AI off. |
| `AI_ENABLED` | `true` | `false` switches AI off even with a key. |
| `NVIDIA_CHAT_MODEL` | `nvidia/nemotron-3.5-bolt-e4b` | General text. |
| `NVIDIA_TRANSLATE_MODEL` | `nvidia/riva-translate-4b-instruct-v2` | Translation. |
| `NVIDIA_BASE_URL` | `https://integrate.api.nvidia.com/v1` | https only (localhost allowed for tests). |
| `AI_TIMEOUT_MS` / `AI_MAX_INPUT_CHARS` / `AI_MAX_OUTPUT_TOKENS` / `AI_RATE_PER_MINUTE` | 15000 / 2000 / 400 / 20 | |

## Rollout order (nothing here has been run against Render)
1. Deploy the code (branch `ai/ten-features` merged by you).
2. Run `npm run db:migrate` against the Render database once (migration `010_event_price_accessibility.sql`, additive).
   Production does not migrate on boot, so a server started before this will refuse to serve until it is run.
3. Add `NVIDIA_API_KEY` in the Render environment and redeploy. Until then every AI button stays hidden.

## Design rules
- Search filters, recommendations and schedule conflicts are deterministic. The model is used only for assistant answers,
  summaries, rewrites, ticket categories, drafts and translation.
- Every model answer is checked (cites given sources, no invented numbers or amenities) and falls back or is discarded.
- Translation covers only languages verified to be correct: Hindi, Spanish, French, German, Italian, Portuguese, Arabic,
  Chinese, Japanese, Korean, Russian. Telugu, Tamil, Bengali, Marathi, Gujarati, Urdu, Kannada, Malayalam and Punjabi returned
  wrong-language text in testing and are excluded.
- Only the text needed for the task is sent to the provider; no names, emails or ids.
