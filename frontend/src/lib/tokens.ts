/**
 * Read a design token (a --color-* custom property from index.css) at runtime.
 * Needed only where a library wants a literal colour string (Leaflet path options);
 * keeps index.css the single source of colour values.
 */
export function colorToken(name: string): string {
  if (typeof document === 'undefined') return 'currentColor'
  const v = getComputedStyle(document.documentElement).getPropertyValue(`--color-${name}`).trim()
  return v || 'currentColor'
}
