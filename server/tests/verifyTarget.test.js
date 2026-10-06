import { describe, expect, it } from 'vitest';
import { classifyDatabaseName } from '../scripts/lib/requireScratchTarget.mjs';

/**
 * The live verifiers (smoke.mjs, verify-socket.mjs, verify-events.mjs) all
 * register users and create events. `classifyDatabaseName` is the rule that stops
 * them running against a real database, so the rule itself is pinned here rather
 * than only being exercised by hand against a live server.
 */

describe('verifier target guard', () => {
  describe('refuses protected databases', () => {
    it.each(['emh', 'postgres', 'template0', 'template1', 'postgis_topology'])(
      'treats "%s" as protected',
      (name) => {
        expect(classifyDatabaseName(name)).toBe('protected');
      }
    );

    it('is case insensitive', () => {
      expect(classifyDatabaseName('EMH')).toBe('protected');
      expect(classifyDatabaseName('Postgres')).toBe('protected');
    });

    it('only ever matches a protected name exactly', () => {
      // `emh` is the real development database, so it is protected. `emh_test` is
      // a different database - the one vitest uses - and is genuinely disposable.
      expect(classifyDatabaseName('emh')).toBe('protected');
      expect(classifyDatabaseName('emh_test')).toBe('scratch');

      // A prefix/suffix must not launder a protected name into acceptance.
      expect(classifyDatabaseName('emh_scratch')).toBe('scratch');
      expect(classifyDatabaseName('postgres_verify')).toBe('scratch');
      expect(classifyDatabaseName('postgres')).toBe('protected');
      expect(classifyDatabaseName('template1_test')).toBe('scratch');
      expect(classifyDatabaseName('template1')).toBe('protected');
    });
  });

  describe('accepts self-declared scratch databases', () => {
    it.each([
      'emh_scratch',
      'emh_test',
      'emh_migration_check',
      'scratch',
      'verify_events',
      'verifier_tmp',
      'probe_db',
      'TEMP_DB',
      'my-testing-copy'
    ])('accepts "%s"', (name) => {
      expect(classifyDatabaseName(name)).toBe('scratch');
    });
  });

  describe('refuses anything unproven', () => {
    it.each(['events_hub', 'production', 'main', 'staging', 'live_db', 'app'])(
      'refuses the plausible real name "%s"',
      (name) => {
        expect(classifyDatabaseName(name)).toBe('unknown');
      }
    );

    it.each([undefined, null, '', '   ', 42, {}])('refuses a non-name: %s', (name) => {
      expect(classifyDatabaseName(name)).toBe('unknown');
    });
  });

  it('defaults to refusing rather than allowing', () => {
    // The safe default matters most: an unrecognised name must never pass.
    const verdicts = ['emh', 'events_hub', 'whatever', '', undefined].map(classifyDatabaseName);
    expect(verdicts).not.toContain('scratch');
  });
});