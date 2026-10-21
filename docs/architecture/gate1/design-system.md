# The Ascent — proposed visual system

**Gate 1 design proposal for #259, not an approved visual contract.** The product owner must approve the exact rendered screens, themes and states before large-scale integration. [Review gallery](README.md) · [Data truth and interactions](data-and-interactions.md).

## Direction and composition

Two compositions were considered: a dense mission dossier, with progression surrounding a central task, and an engineering atlas, with a quiet dimensional centerpiece and connected capabilities. The atlas was selected because it gives CodeFit an identity without turning practical developer work into a decorative game HUD. The mission dossier survives where useful: the Mission Console has an ordered list and an adjacent inspectable briefing.

The original CodeFit monogram, faceted capability core, orbital construction lines, octagonal skill nodes, and mission emblems are authored SVGs. Their role is specific: the core introduces the discover → practice → transfer journey; paths communicate prerequisites; state icons identify available, active, completed-example and locked-example nodes. No anime artwork, character, logo, stock image or remote asset is used. The geometric core is an illustration, not a measurable balance, skill percentage or reward.

Command Center uses one strong hero, a linear progression path, one active sample mission, a session checklist, a small journal and a future encounter concept. Ascension Tree makes the map the central surface and gives evidence a dedicated inspector. Mission Console uses typographic hierarchy and ordered mission rows, with difficulty, time and status subordinate to the task itself. Navigation, controls and provenance remain consistent across all three.

## Tokens

Tokens live in [`desktop/src/style.css`](../../../desktop/src/style.css). The themes are designed independently; light uses mineral ivory, blue-gray ink and saturated teal rather than reversing dark colors.

| Role | Dark | Light | Use |
| --- | --- | --- | --- |
| Canvas | `#0a101c` | `#f3f2ed` | Main workspace |
| Rail | `#080d17` | `#e9eae5` | Persistent navigation |
| Primary surface | `#101a29` | `#fafaf7` | Inspection and focused content |
| Secondary surface | `#132033` | `#e7eceb` | Controls and nested context |
| Raised surface | `#1a293c` | `#e0e7e7` | Segmented selection |
| Primary text | `#edf3f9` | `#182f3e` | Titles and key copy |
| Secondary text | `#9aaabd` | `#526778` | Explanations |
| Metadata text | `#8193ab` | `#526a7b` | Readable tertiary information |
| Cyan / teal | `#7adce8` | `#16677a` | Active paths, selection, actions |
| Violet | `#b3a5ec` | `#6c50a3` | Alternative capabilities, concept context |
| Primary action | `#a4ecf4` on `#082633` text | `#204f65` on white text | Deliberate next action |

Colors never carry state alone. Nodes include distinct completion, lock, activity and availability glyphs; selected buttons expose `aria-pressed`. Badges use text. No gold earned-reward signal is invented.

Space Grotesk provides headings and key numerals; Inter provides body and controls. Variable TrueType files are locally bundled, with both SIL Open Font Licenses and upstream/hash metadata under [`desktop/public/fonts`](../../../desktop/public/fonts). Normal copy is generally 12–13 px, page titles 30–34 px, hero type 37–43 px, and section titles 19–27 px. Badges/metadata use 9–11 px. Nonessential coordinate inscriptions can be smaller; they are decorative and hidden from accessibility APIs. Code text uses the platform monospace fallback.

Spacing uses a 4 px base, with 8/12/16/20/24/32 steps. Desktop content has 38 px gutters; narrow has 24 px. Surfaces use thin borders and restrained 2–3 px radii; octagonal geometry is reserved for capability nodes and identity. Glow is confined to active map focus. Divider lines and filled surfaces remain the primary structure.

## Components and behavior

- `components.tsx`: Button, Badge, SectionTitle, StateView, native modal Dialog and shared Arrow/Home/End tab keyboard behavior.
- `icons.tsx`, `art.tsx`: original line icon set, monogram, core and mission emblems. SVGs scale at high DPI.
- `command-center.tsx`: hero, illustrative progression, sample checklist and journal/encounter entry points.
- `ascension-tree.tsx`: SVG paths plus native HTML buttons, roving tab controls, selected-node inspector, prerequisite navigation, evidence scenarios, branch emphasis, zoom and scroll pan.
- `mission-console.tsx`: search/status filters, selected mission briefing, bookmark toggle and a visible primary action before lengthy objectives.
- `mission-dialog.tsx`: briefing, editable sample workspace, simulated evaluation and guarded departure.
- `library.tsx`: the preserved strict Java response parser and isolated read workflow; remains mounted after first visit so navigation cannot overlap a replacement read.

Hover and selection transitions take about 160–200 ms. Dialog entry is a short opacity/translation transition; only the held loading specimen pulses. `prefers-reduced-motion: reduce` disables all CSS animation/transitions and smooth tree scrolling. No perpetual ambient movement, canvas render loop or network animation is required.

## Responsive contract

At 1280×900 the rail is 208 px, Command Center uses paired columns, the tree has an adjacent inspector, and Mission Console uses list/detail columns. At 760×900 the rail becomes a 72 px icon rail with accessible names and native tooltips. The tree keeps readable node labels and places the inspector below the map, reached with an explicit “Inspect [skill]” control; it never compresses the graph into unreadable badges. Zoom enlarges its internal scrollable canvas from 100% to 150%. Mission list and brief remain side by side; the task action precedes objectives. Vertical scrolling is intentional. No whole-document horizontal overflow is permitted at either width.

## Accessibility and scope

The shell has landmarks and a keyboard skip link that focuses main without changing the URL (the IPC owner check requires the exact document URL). Navigation announces the new page and focuses its heading. All controls are native buttons, inputs, selects or links. Modal dialogs use the platform `<dialog>` focus trap, Escape cancellation and focus restoration. Tabs support arrow keys, Home and End. Skill buttons support arrow keys, Home and End with Enter/Space selection. The unknown scenario removes example completion/history panels; simulated status and data provenance are visible.

Representative text token pairs are automatically checked at 4.5:1 or above in both themes, including metadata on all five base surfaces. This is a limited token check, not a full WCAG audit. Real native screen-reader behavior on Windows/macOS, OS high contrast settings and native platform rendering remain unverified. Human visual approval is outstanding.
