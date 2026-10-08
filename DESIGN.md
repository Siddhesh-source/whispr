---
# gstack: design-md-format=spec
name: Whispr
description: Quiet confidence. Near-monochrome, precise and calm; color appears only when it means trust, attention or danger.
colors:
  fog: "#F4F5F3"
  surface: "#FFFFFF"
  sunken: "#ECEEEB"
  ink: "#14181A"
  text-muted: "#5D6562"
  text-faint: "#8A928E"
  hairline: "#DDE1DD"
  seal: "#C4851F"
  seal-soft: "#F6ECD9"
  on-seal: "#14181A"
  danger: "#C23F2B"
  danger-soft: "#F8E3DF"
  bubble-out: "#14181A"
  on-bubble-out: "#F4F5F3"
  bubble-in: "#FFFFFF"
  dark-fog: "#0D1011"
  dark-surface: "#161A1C"
  dark-sunken: "#1D2224"
  dark-ink: "#E9ECEA"
  dark-text-muted: "#9AA39F"
  dark-text-faint: "#6E7773"
  dark-hairline: "#262C2E"
  dark-seal: "#E2A447"
  dark-seal-soft: "#2E2516"
  dark-danger: "#EE7A66"
  dark-danger-soft: "#3A1E19"
  dark-bubble-out: "#E3E7E4"
  dark-on-bubble-out: "#111416"
  dark-bubble-in: "#1D2224"
typography:
  display:
    fontFamily: Bricolage Grotesque
    fontWeight: 600
    fontSize: 34sp
    letterSpacing: -0.02em
  title:
    fontFamily: Onest
    fontWeight: 600
    fontSize: 22sp
  body:
    fontFamily: Onest
    fontSize: 15.5sp
    lineHeight: 1.38
  label:
    fontFamily: Onest
    fontWeight: 500
    fontSize: 12.5sp
  mono:
    fontFamily: JetBrains Mono
    fontFeature: tnum
rounded:
  xs: 6px
  sm: 10px
  md: 12px
  lg: 16px
  bubble: 18px
  full: 9999px
spacing:
  xxs: 2px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  2xl: 32px
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.fog}"
    rounded: "{rounded.md}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    borderColor: "{colors.hairline}"
    rounded: "{rounded.sm}"
  input:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.hairline}"
    rounded: "{rounded.md}"
  bubble-outgoing:
    backgroundColor: "{colors.bubble-out}"
    textColor: "{colors.on-bubble-out}"
    rounded: "{rounded.bubble}"
  bubble-incoming:
    backgroundColor: "{colors.bubble-in}"
    borderColor: "{colors.hairline}"
    rounded: "{rounded.bubble}"
  unread-badge:
    backgroundColor: "{colors.seal}"
    textColor: "{colors.on-seal}"
    rounded: "{rounded.full}"
  settings-group:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.hairline}"
    rounded: "{rounded.lg}"
---

# Whispr

## Overview

**Creative North Star:** quiet confidence. A private messenger that feels calm,
crafted and adult: near-monochrome surfaces, one disciplined typeface, and color
spent only on meaning.
**Product context:** Whispr, an end-to-end encrypted Android messenger with no
phone number, for people who want privacy without ceremony. Peers: Signal,
Threema, Wire, Session.
**Mode per surface:** Operate (chats, conversation, settings, groups); Persuade
(onboarding, empty states); Read (safety numbers, notices).
**Reference sites:** signal.org, threema.com, wire.com, getsession.org
(category), linear.app (restraint and craft).
**Key characteristics:**
- Ink and fog, not a brand color, carry the interface.
- The amber seal is the only accent and always means trust or attention.
- Flat lists divided by hairlines; no stacked cards.
- Large, narrow Bricolage Grotesque titles give each screen a voice.
- Keys and numbers are set in mono so they read as data.

## Colors

**Strategy:** Restrained. Neutrals do the work; one accent (seal amber) marks
verified contacts, unread counts and the onboarding mark; danger red marks key
changes, failed sends and destructive actions. Nothing else is colored.
Competitors each own a loud hue (Signal blue, Wire blue, Session green); Whispr
owns the absence of one.

**Light or dark:** both, following the system setting. Phones are used in
daylight and at night in bed; neither is the "real" theme.

- `ink` is text, the primary button and your own message bubbles.
- `fog` is the ground; `surface` lifts sheets, groups and incoming bubbles;
  `sunken` fills inputs, search and avatars.
- `hairline` draws dividers and 1px borders; it replaces shadows on most
  surfaces.
- Dark mode is not an inversion: grounds step up in lightness (`dark-fog` →
  `dark-surface` → `dark-sunken`) so hierarchy survives, and outgoing bubbles
  flip to a pale ink so your messages stay the strongest element.
- QR codes stay black on white in both themes for reliable scanning.

## Typography

- **Onest** (SIL OFL 1.1, variable 100–900) for all UI and message text. A
  calm, open grotesk with good Latin and Cyrillic coverage; legible at 15–16sp.
- **Bricolage Grotesque** (SIL OFL 1.1, variable weight/width/optical size) for
  large screen titles and the onboarding headline only, at width 80 and
  weight 600. Never for body text, buttons or anything under 22sp.
- **JetBrains Mono** (SIL OFL 1.1) for safety numbers, account IDs, keys and
  usernames shown as identifiers.

All three are bundled as font resources (no Google Fonts provider: it would make
a third-party request through Play Services). Sizes are in sp so they follow
the user's font scale.

Scale: display 34 / title 22 / headline 18 / name 16 (600) / message 15.5 /
body 14.5 / meta 12.5. Levels differ by size and weight, never weight alone.

## Layout

- 16dp side gutter everywhere; content max width 640dp on large screens.
- List rows are 72dp minimum with a 46dp avatar; dividers start at the text
  column (76dp inset), not at the screen edge.
- Settings are grouped in `surface` panels with 16dp radius and hairline
  borders, under small muted section labels.
- Conversation: 4dp between bubbles in a run, 10dp between runs; the composer
  and top bar are separated from the thread by hairlines, not elevation.

## Elevation & Depth

Flat by default. Depth comes from the fog → surface → sunken steps and 1px
hairlines. The only shadow is on transient layers (bottom sheets, menus,
dialogs): a soft, offset shadow, never a glow.

## Shapes

Radius hierarchy: 6 (bubble tail corner, small chips) · 10 (secondary buttons,
switches' tracks are full) · 12 (primary buttons, inputs, composer, send) ·
16 (settings groups, sheets, cards) · 18 (message bubbles) · full (avatars,
badges). No pill-shaped buttons. Nested radius = outer radius − gap.

## Components

- **Buttons:** primary is solid ink, 12dp radius, 600 weight; secondary is
  surface with a hairline border. Pressed state darkens 8%; disabled drops to
  38% content alpha. Focus shows a 2dp ink outline offset 2dp.
- **Bubbles:** outgoing ink, incoming surface with hairline border, 18dp radius
  with a 6dp tail corner at the end of a run. Time and ticks inside, at 62%
  alpha. Failed sends use `danger-soft` with danger text.
- **Verified seal:** a 15dp amber seal glyph after the contact name. It appears
  only when the safety number has been compared.
- **Unread badge:** amber with ink text. It is the only colored element in a
  quiet chat list, which is why it works.
- **Warnings (key change):** `danger-soft` panel, danger title, ink body, a
  primary and a secondary button. Never a toast.
- **Empty and error states:** a short title in the display face, one muted
  sentence, at most one primary action.

## Do's and Don'ts

- Do keep every screen near-monochrome; check that color appears only for seal,
  badge or danger.
- Do separate list items with inset hairlines, not cards.
- Do set identifiers (safety numbers, IDs) in JetBrains Mono.
- Do take every color, size and radius from `WhisprTheme`; `checkDesignTokens`
  fails the build on literals.
- Don't color outgoing bubbles with an accent.
- Don't add icons in colored circles, gradients, glows or illustrations.
- Don't use Bricolage Grotesque below 22sp or for body text.
- Don't use pill-shaped buttons or a uniform large radius on everything.
- Don't use amber for anything that isn't trust or attention.

## Motion

- **Approach:** minimal-functional.
- **Easing:** enter ease-out, exit ease-in, move ease-in-out.
- **Duration:** micro 90ms (press), short 180ms (sheets, menus), medium 280ms
  (screen transitions).
- **The one authored moment:** when a contact becomes verified, the seal scales
  from 0.6 to 1.0 with a short settle (280ms), once.

## Decisions Log
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-10-08 | Initial design system created | /design-consultation: "quiet confidence"; research on signal.org, threema.com, wire.com, getsession.org and linear.app showed every messenger owns a loud hue, so Whispr is near-monochrome with one trust accent |
