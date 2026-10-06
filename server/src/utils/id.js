import { randomBytes } from 'node:crypto';

/** Collision-resistant, sortable-enough id: `evt_lq3k9x_a1b2c3d4`. */
export function newId(prefix) {
  return `${prefix}_${Date.now().toString(36)}${randomBytes(3).toString('hex')}`;
}