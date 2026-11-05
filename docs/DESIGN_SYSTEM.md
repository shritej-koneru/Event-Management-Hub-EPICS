# EventHub design system — "Campus ops"

One visual language for the manager console (web) and the attendee app (Android).
Calm, practical, information-first. It should read like a well-made operations tool,
not a marketing page.

## Principles
1. **Content over decoration.** No gradients, glows, glassmorphism, or drop shadows used
   for "depth". Structure comes from hairline borders, spacing and type.
2. **One accent.** Teal is the only brand colour. It marks primary actions, the current
   selection and links — nothing else. Status colours are reserved for status.
3. **Numbers are data.** Tabular figures, left-aligned labels, units spelled out.
4. **Dense where people work, roomy where people read.** Tables and lists are compact;
   message threads and announcements get line-height.
5. **No emoji as UI.** Event "icons" are replaced by a category tag (text + small colour dot).

## Colour tokens
| Token | Light | Use |
|---|---|---|
| `paper` | `#F7F5F0` | app/page background (warm off-white) |
| `surface` | `#FFFFFF` | cards, tables, inputs, sheets |
| `surface-sunken` | `#F0EDE6` | table header row, selected row tint base, code/meta chips |
| `line` | `#E3DFD6` | hairline borders, dividers (1px) |
| `line-strong` | `#CFC9BC` | input borders, focused-row border |
| `ink` | `#1C1F1E` | primary text |
| `ink-2` | `#4A4F4C` | secondary text |
| `ink-3` | `#6B706C` | tertiary text, placeholders, timestamps |
| `accent` | `#0F6E6E` | primary buttons, links, active nav, focus ring |
| `accent-hover` | `#0B5858` | hover/pressed |
| `accent-soft` | `#E3F0EE` | selected row / active nav background, accent chips |
| `on-accent` | `#FFFFFF` | text on accent |
| `success` | `#2E7D4F` (soft `#E4F2E9`) | open / live / sent |
| `warning` | `#8F540C` (soft `#FBEFDC`) | needs attention, unread, approximate position |
| `danger` | `#B3261E` (soft `#FBE7E5`) | destructive, errors, closed-with-issue |
| `info` | `#2F5D8A` (soft `#E6EEF6`) | neutral notices, "all attendees" |

Category dots (events): Tech `#2F5D8A`, Cultural/Arts `#8A3B6B`, Entertainment `#A86410`,
Wellness `#2E7D4F`, Career `#0F6E6E`, Sports `#B3261E`, other `#7A7F7B`.

Map: walkways `#9AA19C`, route `#0F6E6E` (3px), start `#2E7D4F`, destination `#B3261E`,
manager-placed place = solid accent dot, approximate place = hollow `ink-3` ring.

Contrast (checked): `ink` / `ink-2` on `paper` and `surface` ≥ 7:1; `ink-3` 5.0:1 on `surface`, 4.6:1 on `paper`; `warning` 5.4:1 on `warning-soft`;
`on-accent` on `accent` ≥ 5.5:1. Never put `ink-3` text on `surface-sunken` below 14px.

## Type
- Web: **IBM Plex Sans** (400/500/600) self-hosted via `@fontsource/ibm-plex-sans`;
  **IBM Plex Mono** 500 only for ids/codes. Android: system sans (Roboto) with the same
  scale, `fontFeatureSettings = "tnum"` on numbers.
- Scale (px / line-height): Display 28/34 600 · Title 20/28 600 · Heading 16/24 600 ·
  Body 14/22 400 · Body-strong 14/22 500 · Small 13/18 400 · Caption 12/16 500 (sentence case,
  `ink-3`) · Overline 11/16 600 uppercase +0.06em tracking (table headers, section labels only).
- Numbers in stats/tables: tabular figures. Dates: `Thu 6 Nov` / `6 Nov, 14:30` (24 h).
- Buttons: sentence case, 500 weight. **No all-caps buttons.**

## Spacing, shape, elevation
- 4px base: 4, 8, 12, 16, 20, 24, 32, 40, 48.
- Radius: 6px controls/buttons/chips, 8px cards/panels/sheets, 999px only for avatars and
  status dots. Nothing above 8px.
- Elevation: none by default. Overlays (menus, dialogs, bottom sheets) get
  `0 8px 24px rgba(28,31,30,0.12)` + 1px `line` border. Cards are flat with a 1px border.
- Focus: 2px `accent` ring, 2px offset, on every interactive element.
- Touch targets ≥ 44px (web) / 48dp (Android).

## Components
- **Top bar (web):** 56px, `surface`, bottom hairline. Left: wordmark "EventHub" in ink 600
  with a 3×16px teal bar before it; right: help-desk open count, account menu.
- **Side nav (web, ≥1024px):** 232px, `paper`, items 36px tall, icon 18px stroke 1.75,
  active = `accent-soft` bg + `accent` text + 2px left accent bar. Section label Overline.
  Mobile: bottom bar 56px, `surface`, top hairline, active item accent text + 2px top bar.
- **Page header:** Title + one-line `ink-2` description on the left, primary action on the
  right. No hero banners.
- **Stat strip:** one bordered `surface` panel divided into cells by vertical hairlines;
  each cell: Caption label on top, value 24/28 600 tabular below, optional delta in
  `success`/`warning`. Not separate floating cards.
- **Table:** `surface`, 1px border, 8px radius; header row `surface-sunken` with Overline
  labels; rows 44px, hairline separators, hover `paper`, selected `accent-soft`. Numbers
  right-aligned. Row actions as quiet icon buttons revealed on hover/focus (always visible
  on touch).
- **Buttons:** Primary = solid `accent`, `on-accent` text, 36px (web) / 48dp (app);
  Secondary = `surface` + 1px `line-strong` + `ink`; Quiet = text-only `accent`;
  Danger = Secondary style with `danger` text; destructive confirm = solid `danger`.
- **Inputs:** 36px (web) / 52dp (app), `surface`, 1px `line-strong`, 6px radius, label
  above in Small 500 `ink-2`, helper/error below in Small. Focus border `accent` + ring.
- **Chips/badges:** 20–24px tall, 6px radius, soft background + strong text of the same
  status colour, Caption weight 500. Status words: "Open", "Closed", "Live", "All attendees".
- **List rows (app):** 64–72dp, leading category dot or avatar initials (32dp circle,
  `surface-sunken`), title Body-strong, meta Small `ink-3`, trailing chevron or badge,
  hairline separators inset 16dp. No card-per-row.
- **Chat thread:** attendee/other bubbles `surface` + 1px `line`; own bubbles `accent-soft`
  with `ink` text (not solid teal); 8px radius with the tail corner 2px; sender + time
  as Caption above the bubble group. Composer: bordered input + solid accent send icon
  button, sticky to bottom with top hairline.
- **Empty states:** one line of `ink-2` + one Quiet action. No illustrations, no emoji.
- **Map canvas:** `surface` background with a faint 24px grid (`line` at 40% opacity),
  labels Small 500 `ink` on `surface` with 1px `line`, 4px radius.

## Motion
150ms ease-out for hover/press/colour; 200ms for sheets and panels. No bouncing, no scale
on press beyond 0.98 on Android ripple defaults. Respect `prefers-reduced-motion`.

## Voice
Plain, specific, sentence case. "3 chats need a reply", not "Welcome back! 👋".
Errors say what happened and what to do next.
