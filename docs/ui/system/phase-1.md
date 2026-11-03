# The System — native visual foundation

Phase 1 of [#244](https://github.com/MrH3lmy/CodeFit/issues/244), implementing
[#249](https://github.com/MrH3lmy/CodeFit/issues/249). This establishes shared chrome and
component vocabulary. Command Center / Ascension Tree (#250), Mission Console (#251) and
Boss Raids (#252) are separate work. The epic's textual palette/component specification is the
available reference; no HTML prototype or licensed anime assets are bundled.

## Gap analysis and baseline

Started from `bc11a7a` on main, including #240, #242 and #254. Checked open PRs before editing;
none were returned. #240's authoritative rewards and Evolution controller changes are merged.
#242's bulk catalog reads **and** workspace lifecycle/autosave cleanup are merged. This change
preserves those files and behaviors.

| Existing capability | Reuse / bounded change |
| --- | --- |
| Persistent `AppShellController`, `NavigationService`, `Route` | Keep content swapping and lifecycle leave/dispose. Place the topbar beside the full-height sidebar, never rebuild it on navigation. |
| Sidebar text buttons and scene width listener | Keep actions and labels. Add original vector icons, cyan selection, scrolling at short heights and a 168px narrow width (220px desktop). |
| Eleven semantic theme tokens, Dark/Light, legacy preference migration | Preserve all eleven; add selection and on-fill ink in both themes. Fix existing white-on-accent overrides for the brighter dark palette. |
| Shared buttons, panels, fields and progress styles | Reuse names; add opt-in hero, mission card, badge, skill node and static status styles. |
| Review / assessment / guided focus mode | Keep sidebar/topbar hidden and unmanaged; editor/review contents have no decorative effects. |
| Existing Today dashboard hero | Apply shared hero styling and wrapping action layout to existing data/actions; correct FXML class-list syntax. No new recommendations or statistics. |
| Evolution header | Wrap the existing heading and preserve Refresh width at 760px; no controller or workflow changes. |
| Existing routes | Keep every route. Correct Settings selection and clear stale navigation selection for global actions. |
| Exposure, frozen submissions, XP, P2P and SQLite | No changes. No data migration, new network operation or policy calculation. |

## Visual contract

### Palette

`tokens.css` is authoritative. Original tokens remain available to all screens. Three extensions
(`selection`, `on-accent`, `on-status`) give distinct cyan focus/selection and readable filled controls.

| Role | Dark | Light |
| --- | --- | --- |
| Background | `#090D19` | `#F0F3FA` |
| Surface / raised | `#151E31` / `#1B2840` | `#FFFFFF` / `#E7EDF8` |
| Border | `#354664` | `#8594AE` |
| Primary / secondary ink | `#EDF3FF` / `#A8B8D2` | `#142139` / `#4C5D77` |
| Selection / keyboard focus | `#74E8FF` | `#006C83` |
| Accent / progression | `#AF98FF` | `#6542B2` |
| Reward / warning | `#FFD184` | `#835700` |
| Success / danger | `#6BE3B9` / `#FF8C9D` | `#146C50` / `#B32641` |
| On accent / on status | `#090D19` | `#FFFFFF` |

Secondary dark ink is slightly brighter than the reference for small text. Semantic foregrounds
are tested at 4.5:1 against each surface; filled actions have independently tested ink. Borders
separate surfaces, not communicate status alone. These checks are not an accessibility certification.

### Hierarchy and spacing

- Native system fonts (`Inter`, `Segoe UI`, sans-serif fallback); no remote font downloads.
- Title 28px, topbar/section heading 18px, body 13–14px, helper 12px, chrome eyebrow 10–11px.
- 4/8px spacing rhythm; cards 18–24px inset, 12px radius; hero 24px / 14px; buttons 8px radius.
- Surface depth uses borders and a subtle hero gradient. No blur, particle effects, pulsing or shadows
  over source editors. Cyan indicates selection, violet is a progression accent, gold a reward accent;
  color never calculates or implies mastery.
- Sidebar controls retain a 40px minimum target. At <920px the sidebar contracts to 168px and omits
  only its explanatory footer line. Labels, accessible names and every destination remain present.
- At 760px the content host retains 592px before content padding. Short windows scroll the navigation
  rather than shrinking buttons. The topbar/title wraps. Existing screens retain their own scrolling.

### Shared component API

Use normal JavaFX layout/controls with these style classes; no new renderer or routing layer:

| Component | Contract |
| --- | --- |
| `system-hero`, `system-title`, `system-eyebrow` | Page objective and context; labels must wrap. No fabricated profile/rank. |
| `mission-card` | VBox/container for an authorized mission summary. Caller still owns exposure-before-content. |
| `system-badge` + `-info`, `-reward`, `-success` variants | Text explicitly describes the state; no meaning conveyed by color alone. |
| `skill-node`, `skill-node-unavailable`, `skill-node-supported` | Native Button with descriptive text; dashed unavailable and solid supported borders. Inspectable unavailable nodes need not be disabled. Caller owns eligibility. |
| `SystemNotice(Kind, title, detail)` | EMPTY / LOADING / ERROR / SUCCESS / INFO, static text with wrapping. Contains no timers, work, retry or evidence logic; owner may add its own actions. |
| Existing `primary-button`, `ghost-button`, progress bars | Keep semantic roles; actual progress value supplied by the owning service. |
| `:invalid`, `validation-message` | Field border plus an explicit explanation supplied by the controller. |
| Existing `review-card`, text areas, editor styles | Plain, high-contrast content; no decorative motion. |

Keyboard focus uses a 2px cyan (dark) / teal (light) outline, distinct from the active navigation's
left-edge selection. Active destination is included in the button's accessible text. Disabled controls
retain 75% opacity and native disabled semantics. No action or information depends on hover.

**Reduced motion:** the new foundation is static for everyone. `SystemNotice.LOADING` uses text,
not an indeterminate animated indicator; shared TitledPane expansion has animation disabled.
No new motion preference, database setting or cosmetic timer is needed. Existing runner/busy indicators
and task semantics are unchanged; this is not a claim that every legacy animation has been removed.

## Verification and screenshots

See [verification.md](verification.md) for commands, capture matrix, results and platform limits.
Screenshots use a fresh temporary SQLite profile. The component reference is an explicit test-only
specimen, not a hidden product route, assessment, or claim about a real learner.

## Phase 2 handoff

After Phase 1 review/merge, #250 can compose these primitives with `PlayerSystemService`, exact
Skill Graph definitions and mastery/adaptation outputs. Keep unknown/stale/assisted evidence visible;
never turn XP into a skill percentage. Build the Ascension Tree with keyboard-inspectable nodes and
maintain content authorization before any challenge details. Do not replace the persistent shell.

Native Windows/macOS, OS text scaling and screen-reader verification remain in [#238](https://github.com/MrH3lmy/CodeFit/issues/238).
Use its existing matrix instead of creating a duplicate validation issue. In particular verify focus
visibility/announcements, long labels, compact navigation scrolling and saved-submission recovery.
