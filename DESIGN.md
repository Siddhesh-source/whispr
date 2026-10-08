---
# gstack: design-md-format=spec
name: Whispr
description: Quiet confidence in mint and moss. Calm green surfaces, precise type, and an amber seal that only ever means trust or attention.
colors:
  ground: "#F2F5EF"
  surface: "#FFFFFF"
  sunken: "#E7ECE4"
  ink: "#16201A"
  text-muted: "#5D6A60"
  text-faint: "#77847B"
  hairline: "#DDE4D8"
  primary: "#2C6E49"
  on-primary: "#F2F8F3"
  bubble-out: "#CDEBD8"
  on-bubble-out: "#102A1C"
  meta-out: "#486B58"
  read-tick: "#237A4E"
  bubble-in: "#FFFFFF"
  seal: "#C4851F"
  seal-soft: "#F6ECD9"
  on-seal: "#16201A"
  danger: "#B83A27"
  danger-soft: "#F8E3DF"
  dark-ground: "#0F1410"
  dark-surface: "#161D18"
  dark-sunken: "#1B241E"
  dark-ink: "#E5ECE6"
  dark-text-muted: "#95A49A"
  dark-hairline: "#26302A"
  dark-primary: "#86D7A8"
  dark-on-primary: "#0C1A11"
  dark-bubble-out: "#244D36"
  dark-on-bubble-out: "#E8F5EC"
  dark-meta-out: "#A3C7B1"
  dark-read-tick: "#86E0AE"
  dark-bubble-in: "#1B241E"
  dark-seal: "#E2A447"
  dark-danger: "#EE7A66"
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
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
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

**Creative North Star:** quiet confidence in mint and moss. A private
messenger that feels calm, crafted and adult: soft green surfaces, one
disciplined typeface, and an amber seal spent only on meaning.
**Product context:** Whispr, an end-to-end encrypted Android messenger with no
phone number, for people who want privacy without ceremony. Peers: Signal,
Threema, Wire, Session.
**Mode per surface:** Operate (chats, conversation, settings, groups); Persuade
(onboarding, empty states); Read (safety numbers, notices).
**Reference sites:** signal.org, threema.com, wire.com, getsession.org
(category), linear.app (restraint and craft).
**Key characteristics:**
- Your messages sit in mint bubbles; theirs in white with a hairline.
- Moss green carries actions: buttons, send, switches, the new-chat button.
- The amber seal marks verified contacts and unread counts, nothing else.
- Flat lists divided by inset hairlines; settings in quiet grouped panels.
- Large, narrow Bricolage Grotesque titles; keys and IDs in mono.

## Colors

**Strategy:** Committed, softly. Green owns the product (mint for your side of
the conversation, moss for actions), neutrals are tinted toward it, amber is
the one accent for trust and attention, and red is reserved for danger. Mint
and moss were chosen over a deep "evergreen" bubble and over sage: the
friendliest of the three while staying calm (variants in
`~/.gstack/projects/Siddhesh-source-whispr/designs/`).

**Light or dark:** both, following the system setting.

- `primary` (moss) is the primary button, send, switches, focus and the
  new-chat button. Dark mode uses a light mint-green primary on near-black.
- `bubble-out` (mint) holds your messages; `meta-out` is their time and ticks;
  `read-tick` marks a read message.
- `ground` is the background; `surface` lifts incoming bubbles, settings groups
  and the composer; `sunken` fills avatars and disabled controls.
- `hairline` draws dividers and 1px borders instead of shadows.
- `seal` (amber) is the verified seal and the unread badge, nothing else.
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
- Conversation: the thread is anchored to the composer (a short chat sits at
  the bottom, not under the header). 2dp between bubbles in a run, 12dp
  between runs, 16dp before a day divider. A run is one author's messages
  with no pause over 5 minutes and no midnight in between. Day dividers are a
  small `sunken` label: "Today", "Yesterday", or the date.

## Elevation & Depth

Flat by default. Depth comes from the ground → surface → sunken steps and 1px
hairlines. The only shadow is on transient layers (bottom sheets, menus,
dialogs): a soft, offset shadow, never a glow.

## Shapes

Radius hierarchy: 6 (bubble tail corner, small chips) · 10 (secondary buttons,
switches' tracks are full) · 12 (primary buttons, inputs, composer, send) ·
16 (settings groups, sheets, cards) · 18 (message bubbles) · full (avatars,
badges). No pill-shaped buttons. Nested radius = outer radius − gap.

## Components

- **Buttons:** primary is solid moss, 12dp radius, 600 weight; secondary is
  surface with a hairline border. Pressed state darkens 8%; disabled drops to
  38% content alpha. Focus shows a 2dp ink outline offset 2dp.
- **Bubbles:** outgoing mint, incoming surface with a hairline border, 18dp
  radius with a 6dp tail corner at the end of a run. Failed sends use
  `danger-soft` with danger text.
- **Time and ticks:** one group, always at the bottom end of the bubble: timer
  icon (disappearing messages only), time in tabular figures, then the tick
  (one for sent, two for delivered, two in `read-tick` once read), all 15dp
  and evenly spaced. The group sits after the last line when it fits there,
  otherwise on its own line, flush right. Never on a line of its own when it
  fits inline.
- **Conversation header:** back, the contact's name with the seal when
  verified, the timer menu and the verify action. No status strip.
- **Disappearing strip:** shown only while a timer is on (off by default), one
  muted line under the header: timer icon and "Messages disappear after
  [time]". Nothing about verification.
- **Composer:** one bordered bar (16dp radius) holding attach, the field, the
  mic while empty, and the square send button.
- **Recording bar:** replaces the composer while the microphone is live:
  discard, a pulsing `danger` dot (the one non-danger use of red: a live
  microphone), elapsed time in tabular figures, a moss level meter, and the
  square send that stops and sends. A failure to start the microphone is
  shown as an error, never silently ignored.
- **Verified seal:** a 15dp amber seal glyph after the contact name. It appears
  only when the safety number has been compared.
- **Unread badge:** amber with ink text. It is the only colored element in a
  quiet chat list, which is why it works.
- **Warnings (key change):** `danger-soft` panel, danger title, ink body, a
  primary and a secondary button. Never a toast.
- **Empty and error states:** a short title in the display face, one muted
  sentence, at most one primary action.

## Do's and Don'ts

- Do keep green to bubbles and actions, amber to seal and badge, red to danger.
- Do separate list items with inset hairlines, not cards.
- Do set identifiers (safety numbers, IDs) in JetBrains Mono.
- Do take every color, size and radius from `WhisprTheme`; `checkDesignTokens`
  fails the build on literals.
- Don't show a verification or status strip in the conversation; the seal
  next to the name is the signal.
- Don't add icons in colored circles, gradients, glows or illustrations.
- Don't use Bricolage Grotesque below 22sp or for body text.
- Don't use pill-shaped buttons or a uniform large radius on everything.
- Don't use amber for anything that isn't trust or attention.
- Don't draw ticks as text glyphs; use the tick icons in the meta group.

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
| 2026-10-08 | Mint & moss palette (variant C), conversation layout B without the trust strip, uniform time/tick group | User review of light/dark variants: black-and-white read too stark; verification strip removed, disappearing strip only when a timer is set |
| 2026-10-08 | Initial design system created | /design-consultation: "quiet confidence"; research on signal.org, threema.com, wire.com, getsession.org and linear.app showed every messenger owns a loud hue, so Whispr is near-monochrome with one trust accent |
