---
version: alpha
name: YT Sync for iBroadcast
description: High-fidelity dark audio utility theme for Jetpack Compose
colors:
  background: "#121212"
  surface: "#181818"
  surface-elevated: "#222222"
  surface-hover: "#2A2A2A"
  primary: "#1DB954"
  on-primary: "#000000"
  text-primary: "#EEEEEE"
  text-secondary: "#AAAAAA"
  text-muted: "#666666"
  border-glass: "#33FFFFFF"
  info: "#29B6F6"
  warning: "#FFB300"
  error: "#E91429"
typography:
  title-lg:
    fontFamily: sans-serif
    fontSize: 18px
    fontWeight: 700
    lineHeight: 1.2
  title-md:
    fontFamily: sans-serif
    fontSize: 14px
    fontWeight: 600
    lineHeight: 1.2
  body-md:
    fontFamily: sans-serif
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.4
  label-mono:
    fontFamily: monospace
    fontSize: 10px
    fontWeight: 700
    lineHeight: 1.2
rounded:
  xs: 4px
  sm: 6px
  md: 8px
  lg: 12px
  full: 9999px
spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
components:
  card-panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
    padding: 12px
  card-option-hover:
    backgroundColor: "{colors.surface-hover}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: 8px
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.sm}"
    padding: 8px
  badge-status:
    backgroundColor: "{colors.surface-elevated}"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.xs}"
    padding: 4px
  badge-info:
    backgroundColor: "{colors.surface-elevated}"
    textColor: "{colors.info}"
    rounded: "{rounded.xs}"
    padding: 4px
  badge-warning:
    backgroundColor: "{colors.surface-elevated}"
    textColor: "{colors.warning}"
    rounded: "{rounded.xs}"
    padding: 4px
  badge-error:
    backgroundColor: "{colors.error}"
    textColor: "#FFFFFF"
    rounded: "{rounded.xs}"
    padding: 4px
  input-field:
    backgroundColor: "{colors.border-glass}"
    textColor: "{colors.text-muted}"
    rounded: "{rounded.sm}"
    padding: 8px
---

# YT Sync for iBroadcast Design System

## Overview

YT Sync for iBroadcast is a tactile, high-density audio batch-processing utility for Android. The visual identity is **Liquid Glass Hi-Fi** — a disciplined, dark studio aesthetic built with Jetpack Compose Material 3.

The interface prioritizes information density, operational clarity, and immediate hardware feel. Rather than playful consumer flourishes, it reflects professional audio hardware: matte dark surfaces, precision hairline borders, high-contrast monospace status badges, and restrained Spotify-green accents.

## Colors

The color palette is grounded in low-reflectance charcoal and dark gray neutrals, with high-contrast text and purposeful functional accents:

- **Background (`#121212`):** Primary canvas tone. Dark charcoal that avoids pitch-black OLED smear while maximizing contrast.
- **Surface (`#181818`):** Base card and container surface. Used for batch queue rows, sheets, and dialog surfaces.
- **Surface Elevated (`#222222`):** Secondary layer for nested controls, inactive buttons, and dialog chrome.
- **Surface Hover / Pressed (`#2A2A2A`):** Interactive feedback state for row selections and option cards.
- **Primary Accent (`#1DB954`):** Spotify Green. Strictly reserved for active state indications, ready sync triggers, and primary confirmation buttons. Used on <= 10% of any view.
- **Text Primary (`#EEEEEE`):** Primary text color for track titles, headers, and active labels.
- **Text Secondary (`#AAAAAA`):** Subdued metadata text for artists, album names, durations, and secondary hints.
- **Text Muted (`#666666`):** Disabled states, empty placeholder hints, and inactive toggles.
- **Glass Border (`#33FFFFFF`):** Translucent 1dp stroke that defines panels and inputs against dark backgrounds.
- **Info (`#29B6F6`):** YouTube metadata lookup, active streaming operations, and search actions.
- **Warning (`#FFB300`):** Retune operations (432Hz pitch shift indicators) and awaiting pick states.
- **Error (`#E91429`):** Download failures, invalid URLs, and network error indicators.

## Typography

Typography balances clean sans-serif UI hierarchy with tactile monospace telemetry:

- **Title Large (`title-lg`):** 18px (18sp) bold sans-serif for screen and sheet headers.
- **Title Medium (`title-md`):** 14px (14sp) semi-bold sans-serif for track titles and card headings.
- **Body Medium (`body-md`):** 12px (12sp) regular sans-serif for metadata lines, descriptions, and list details.
- **Label Monospace (`label-mono`):** 10px (10sp) bold monospace for telemetry badges, file sizes, audio bitrates, and status pills (e.g. `QUEUED`, `METADATA_READY`, `SYNCED`).

## Layout

Layout follows a disciplined 4dp base grid tailored for handheld batch monitoring:

- **Row Density:** Queue rows maintain a compact 64dp–72dp height to maximize visible batch items without feeling cramped.
- **Horizontal Margins:** 16dp outer screen margin, 12dp internal card padding.
- **Vertical Spacing:** 8dp separation between list cards; 4dp between label lines and metadata badges.
- **Action Safe Area:** Primary batch controls anchor to bottom navigation bars or elevated floating action cards with safe insets.

## Elevation & Depth

Depth is achieved through luminance and border delineation rather than drop shadows:

- **Liquid Glass Panel:** Surfaces use a solid dark backing (`#181818`), an optional subtle specular white scrim (alpha 0.08–0.10), and a crisp 1dp vertical gradient border (`Color.White.copy(alpha = 0.12f)`).
- **No Heavy Drop Shadows:** Elevating components via heavy shadows is avoided in dark mode to prevent muddiness. Contrast is created by the 1dp translucent border and surface step (`#121212` -> `#181818` -> `#222222`).

## Shapes

Shapes communicate hierarchy and touch responsiveness:

- **Cards & Sheets (`12px` / 12dp):** Main queue items, bottom sheets, and inspect modals.
- **Dialogs & Secondary Cards (`8px` / 8dp):** Alert dialogs and candidate search option items.
- **Buttons & Action Controls (`6px` / 6dp):** Primary action buttons (`SAVE`, `START`, `KEEP`).
- **Status Badges (`4px` / 4dp):** Compact pill tags displaying pipeline statuses and audio source preferences.

## Components

Key Compose components follow strict styling rules:

- **Queue Row Card:** Uses `Modifier.liquidGlassPanel(cornerRadius = 12.dp, lightweight = true)`. Displays artwork thumbnail (48dp rounded 6dp), title (14sp), artist/album (12sp), and right-aligned monospace status pill.
- **Primary Button:** Container color `#1DB954`, content color `#000000`, rounded 6dp, bold uppercase monospace text (10sp or 12sp).
- **Secondary Button:** Outlined or container `#222222`, content color `#EEEEEE`, 1dp border `#33FFFFFF`, rounded 6dp.
- **Status Pill:** 4dp rounded background with alpha tint matching the status color (e.g. `Color(0x22FFB300)` for warning, `Color(0x221DB954)` for ready), containing 10sp bold monospace text.

## Do's and Don'ts

### Do
- **Do** use `#1DB954` sparingly as a deliberate accent for completion, confirmed matches, and primary triggers.
- **Do** use `FontFamily.Monospace` with 10sp bold text for technical state labels, durations, and pipeline stages.
- **Do** use `Modifier.liquidGlassPanel` for cards and sheets to maintain consistent 1dp hairline border delineation.
- **Do** keep interactive touch targets at least 48dp (`Modifier.minimumInteractiveComponentSize()`).

### Don't
- **Don't** use light or white backgrounds; the entire application is dark-mode only.
- **Don't** add colored drop shadows, neon glows, or multi-color gradients.
- **Don't** nest cards within cards; keep lists to a single surface depth.
- **Don't** write verbose AI filler text; use concise, engineering-focused labels (e.g. "Matching metadata" instead of "We are currently finding your songs").
