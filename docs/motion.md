<!-- Motion and haptics plan (2026-11-19). The owner rules below override the plan. -->

# Owner rules (2026-11-19), which override anything below

The owner, after testing a release build on a phone, said:

- "there is zero animation what so ever in the entire application"
- "There should be opening and leaving animations for each new screens"
- "There should be animation while pressing the button, that is the sole purpose we designed the button like that"
- "there should be animations everywhere its possible"
- "we can use haptics where ever we need"
- "The nav bar item change should also have animation"
- "every animations including tab switching should feel natural and obay physics"

So the lead decided:

1. **Physics first.** Everything that moves, scales or resizes uses a spring (`spring(dampingRatio, stiffness)`),
   not a fixed-duration tween. This covers screen slides, sheets, dialogs, the 3D press and release, the nav pill,
   chips, toggles, list placement and the review card. Each spring starts from the current value and velocity, so an
   interruption continues smoothly instead of jumping or restarting. Fades may stay short tweens (or low-stiffness
   springs on alpha). The duration tokens below are then only targets for choosing spring stiffness.
2. **Gesture velocity carries over.** The review card fling uses the finger's velocity: a decay or spring with the
   release velocity, not a fixed tween. Dragging a sheet and letting go continues with the release velocity, and it
   settles or dismisses by velocity and position. Predictive back follows the finger, then settles with a spring
   from where it was released.
3. **The tab bar animates, with a pill that slides.**
   - One green pill moves from the previous tab to the new tab: its x position and width follow a spring
     (`motion/spring/fast`, with a small overshoot allowed, damping about 0.8). The label fades in at the new tab.
   - Each tab still draws its own dock (see 3.6), so the new dock starts with the pill at the previous tab's slot,
     taken from `LocalTabMemory`, and springs it to the active slot.
   - The tab content arrives with the fade plus a slight scale settle described in the plan.
   - A light `SEGMENT_TICK` haptic plays on a tab change. The owner was told so; the bottom bar works like a
     segmented control.
4. **The 3D press is always visible.** The face springs down onto its edge on touch and springs back up on release,
   with a minimum visible press for a quick tap, including inside scroll containers (see 3.3). Ghost buttons also
   get visible press feedback.
5. **Remove animations is respected**, as the plan describes. Haptics follow the system touch-feedback setting.
6. **Motion is part of the design.**
   - The Figma Design System page gets a "Motion" section with the tokens (springs, haptics) and the patterns.
   - `design/spec/motion.md` is exported from it.
   - No screen frames change.

---

# Motion and haptics plan

Status: being built (2026-11-20); the contract (phase 0) is in. `design/spec/motion.md` holds the values (springs,
durations, distances, haptics) and wins wherever this plan differs. Planned 2026-11-19. It merges the motion
research and the haptics research into one plan. A completeness review checked it against `ui/screens/**`,
`ui/components/**`, the tests and `design/tools/capture.sh`.
Paths are relative to `app/src/main/kotlin/com/expenses/app/` unless they start with `design/`, `docs/` or `app/`.

## 0. Before any code: check the phone

The app already has some motion today:
- Every non-tab screen uses Navigation Compose 2.10.2's default transition, a 700 ms crossfade (checked in the jar).
- With predictive back on, a back gesture already shrinks the leaving screen to 70%.
- AppSwitch slides its knob over 120 ms.

"Zero animation" on that phone may therefore mean that Settings > Accessibility > Color and motion > **Remove
animations** is on, or that the Developer options animator duration scale is 0. Compose ends every animation on the next
frame when that scale is 0. Ask the owner to check both settings, and that Touch feedback is on (Sound and vibration >
Vibration and haptics).

The plan is needed either way. A slow dissolve between two screens with the same background reads as no motion, tabs
cut hard, and a quick tap on a 3D button shows nothing.

## 1. Tokens

All motion values live in `ui/theme/Motion.kt`, and the haptic set lives in `ui/theme/Haptics.kt`. The same names appear
in Figma and in `design/spec/motion.md`. Screens and components never write raw durations or curves.

### 1.1 Durations

| Token | Value | Use |
|---|---|---|
| `motion/duration/press` | 40 ms | 3D face goes down; overlay fades in (50 ms) |
| `motion/duration/short` | 150 ms | exits, fades, colour and border changes, scrim out, small enters |
| `motion/duration/medium` | 250 ms | back (pop), tab content in, dialog card in, sheet out, review fly-out |
| `motion/duration/long` | 300 ms | forward push, modal rise, sheet in, result screen in |
| `motion/hold/press` | 100 ms | minimum time a press stays visible (3D face, overlay, Ghost pill) |
| `motion/delay/placeholder` | 200 ms | loading placeholders fade in only after this |
| Fade-through split | out 90 ms, then in 160 ms (delay 90) | content that swaps in place |
| Cap | 350 ms | no single motion is longer, except the placeholder pulse loop (900 ms per half cycle) |

Exits are always shorter than enters.

### 1.2 Easing (Material 3 values)

| Token | Value | Use |
|---|---|---|
| `motion/easing/emphasized` | `PathEasing("M0,0 C0.05,0 0.133333,0.06 0.166666,0.4 C0.208333,0.82 0.25,1 1,1")` | screen slides, things that start and end on screen |
| `motion/easing/emphasized-decelerate` | `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)` | entering |
| `motion/easing/emphasized-accelerate` | `CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)` | leaving |
| `motion/easing/standard` | `CubicBezierEasing(0.2f, 0f, 0f, 1f)` | colour and alpha on a component that stays |
| `motion/easing/linear` | `LinearEasing` | anything that follows a finger (predictive back, drag release), press down |

### 1.3 Springs

The Compose values are `dampingRatio / stiffness`. The Figma prototype equivalent uses mass 1 and
damping = 2 x ratio x sqrt(stiffness).

| Token | Compose | Figma (mass, stiffness, damping) | Feel | Use |
|---|---|---|---|---|
| `motion/spring/fast` | 0.9 / 1400 | 1, 1400, 67 | settles in about 120 ms, no bounce | switch knob, segmented pill, nav pill label |
| `motion/spring/default` | 0.9 / 700 | 1, 700, 48 | settles in about 170 ms | list placement, review stack, drag return, section expand |
| `motion/spring/pop` | 0.5 / 600 | 1, 600, 24.5 | about 320 ms, 16% overshoot | sticker pop, radio dot, stamp at threshold |
| `motion/spring/press-release` | 0.5 / 1500 (`Spring.DampingRatioMediumBouncy` / `Spring.StiffnessMedium`) | 1, 1500, 38.7 | about 200 ms, about 0.65 dp overshoot | 3D face coming back up |

### 1.4 Distances, scales and state layer

| Token | Value | Use |
|---|---|---|
| `motion/distance/axis` | 30 dp | shared-axis slide (push and pop) |
| `motion/distance/press` | 4 dp (`EdgeHeight - 1`) | 3D face drop; leaves a 1 dp lip of edge, as today |
| `motion/distance/rise` | 24 dp | dialog card rise, stamp, small rises |
| `motion/fraction/modal` | 1/8 of the height | modal screen rise |
| `motion/scale/enter` | 0.96 | result screens, arrival at Home, tab content |
| `motion/scale/dialog` | 0.94 | dialog card |
| `motion/scale/predictive` | 0.90 | leaving screen during a back gesture |
| `state/pressed` | `color/text/primary` at 10% | flat pressed overlay (Material 3 pressed state layer) |
| `state/pressed-ghost` | `color/brand/soft` at 100% | Ghost button pressed pill |

### 1.5 Haptic tokens

| Token | Constant (`HapticFeedbackConstantsCompat`) | When | Fallbacks below API 34 / 30 (core 1.19.1) |
|---|---|---|---|
| `haptic/press` | `VIRTUAL_KEY` | a 3D face goes down; only on phones with rich haptics (see 3.4) | none needed (API 5) |
| `haptic/selection` | `SEGMENT_TICK` | the selection changes in a chip, Me/Family, option card or category | `CONTEXT_CLICK` below 34 |
| `haptic/toggle-on`, `haptic/toggle-off` | `TOGGLE_ON` / `TOGGLE_OFF` | a switch flips | `CONTEXT_CLICK` / `CLOCK_TICK` below 34 |
| `haptic/threshold-in`, `haptic/threshold-out` | `GESTURE_THRESHOLD_ACTIVATE` / `_DEACTIVATE` | review card crosses the commit line, or crosses back | `CONTEXT_CLICK` / `CLOCK_TICK` below 34 |
| `haptic/confirm` | `CONFIRM` | a user-started action completed (money or group outcome) | `VIRTUAL_KEY` below 30 |
| `haptic/reject` | `REJECT` | a user action was refused (validation, server refused, offline) | `LONG_PRESS` below 30 |

Rules for haptics:
- At most one haptic per user action. The one exception is a press followed by a confirm on the next screen.
- A tick (press, selection, toggle, threshold) is dropped if another haptic played less than 80 ms earlier.
- Confirm and reject are never dropped.
- Confirm is posted at least 250 ms after the last press. On a Pixel, `VIRTUAL_KEY` and `CONFIRM` are both
  EFFECT_CLICK, and two of them close together feel like `REJECT`'s double click.

Never play a haptic for:
- tab switches, navigation, Back or scrolling
- typing
- opening a dialog or sheet, or a tap on a scrim
- Cancel, Not now, Done or Undo
- disabled taps
- anything the person did not start (background sync, a new payment card, the passive offline banner)

## 2. What moves where

Owner A is the engineer for navigation, overlays and content. Owner B is the engineer for press, controls, haptics,
review and success screens (see section 6). Priority is must, should or nice.

### 2.1 Navigation (owner A)

| Where | What | Haptic | Priority |
|---|---|---|---|
| Every forward push to a non-tab, non-modal, non-result screen (`ui/nav/ScreenTransitions.kt`, used by `ui/nav/AppNavHost.kt`) | Shared axis X. **Enter:** `slideIntoContainer(Start, initialOffset = 30dp, tween(300, emphasized))` + `fadeIn(tween(200, delayMillis = 100, emphasized-decelerate))`. **Exit:** `slideOutOfContainer(Start, 30dp, tween(300, emphasized))` + `fadeOut(tween(100, emphasized-accelerate))`. `slideIntoContainer` flips the direction in RTL. | none | must |
| Every pop (system Back, top bar back, `leave()`) | The same motion mirrored, at 250 ms. **popEnter:** slide from -30 dp + `fadeIn(160, delay 90)`. **popExit:** slide to +30 dp + `fadeOut(90)`. | none | must |
| Modal routes: `SignIn`, `AddExpense`, `ChooseCategory`, `NewGroup`, `AddFriend`, `ImportSplitwise` | **Enter:** `slideInVertically { it / 8 }` tween(300, emphasized-decelerate) + `fadeIn(150)`. The screen under it: `ExitTransition.KeepUntilTransitionsFinished`, so it does not move. **Pop:** `slideOutVertically { it / 8 }` tween(250, emphasized-accelerate) + `fadeOut(150)`; the screen under it uses `popEnter` None. Modal to modal (Sign in > Your name, New group > Add friend or Import) uses the push. | none | should |
| Result routes: `ExpenseAdded`, `SplitAdded`, `BalanceCleared`, `GroupImported` | **Enter:** `fadeIn(250)` + `scaleIn(0.96, tween(300, emphasized-decelerate))`. **The form under it:** `fadeOut(150, emphasized-accelerate)`. **Leaving by pop:** fade through (out 90, in 160 with delay 90). The sticker pop is in 2.7. | confirm (2.7) | must |
| Arriving at a tab from outside: `finishOnboarding()` to Home, GroupImported "Done" to Groups | "Arrival": `fadeIn(250)` + `scaleIn(0.96, 300)`; the dock comes with the screen. The leaving screen: `fadeOut(150)`. | none | should |
| Tab to tab (bottom nav, the "Owed to you" / "You owe" tiles, Back from a tab to Home) | The destination transition stays **None**, as today, because each tab draws its own dock. `MainScaffold` animates only the content: alpha 0 to 1 over tween(200, standard) and scale 0.96 to 1 over tween(250, emphasized-decelerate), in one `graphicsLayer`. This runs only when the tab was reached from another tab (see 3.6). (Changed after the device check: the old tab stays drawn until the new tab's page covers it; see 3.6.) | none | must |
| `ui/components/BottomNav.kt` pill | Draw all four tabs with one composable whose "selectedness" comes from a transition from the previous tab to the active one. **Label:** `expandHorizontally(spring/fast)` + `fadeIn(150, delay 50)`; the old label uses `shrinkHorizontally(spring/fast)` + `fadeOut(100)`. **Face colour and edge alpha:** 150 ms. **Icon tint:** `textTertiary` to `textOnBrand` over 150 ms. `SpaceBetween` lets the other icons glide. **Press:** the pill gets the 3D press (2.3) without the press haptic, and the overlay its inner Row draws today is removed. | none | should |
| Predictive back (Android 14+; on by default for targetSdk 36 on Android 16): the `NavHost` parameters `predictivePopEnterTransition` and `predictivePopExitTransition` (`(scope, swipeEdge: Int)`, present in 2.10.2) | **Leaving screen:** `scaleOut(0.9)` + slide by width/8 toward the side being swiped (`EDGE_LEFT` moves right) + `fadeOut`, all `tween(250, LinearEasing)` so it tracks the finger. **Screen under it:** slide from -30 dp + `fadeIn`, linear. **Tab to tab:** None, so the gesture shows no preview, as today. Screens with an enabled `BackHandler` get no preview, which is correct there: SplitAdded, Same split, GroupImported, ImportSplitwise, SignInName, Review (sheet open, All sorted), ExpenseDetail (editing), GroupSettings (sheet open), Join (while joining), You (must finish). **After the finger lets go:** the progress springs on to 1 (`motion/spring/screen`, from the finger's speed) before the swipe completes, so the screens settle instead of stopping dead (`ui/nav/SpringBack.kt`, 3.9). | none | must |
| Remove animations | Every `NavHost` lambda returns `EnterTransition.None` / `ExitTransition.None`, which avoids a one-frame flash. | n/a | must |
| Gated screens when signed out: `SplitPayment`, `SplitTyped`, `JoinGroup` (`ui/nav/SignInGate.kt`) | The gate draws nothing and opens Sign in only once RESUMED, which is after the 300 ms push. That gives 600 ms of motion on an empty page. **Fix:** when the target is a gate route and the person is signed out (a `() -> Boolean` passed from `AppRoot` into the transition table), the enter is `fadeIn(150)` only. | none | should |
| `ui/screens/signin/SignInNav.kt` | `offerAutomatically` (the Credential Manager sheet) runs in `LaunchedEffect` at composition, during the modal rise. **Fix:** run it once on the first RESUMED instead. | none | should |
| Cold start: `res/values/themes.xml` `windowBackground` is black | Use the canvas colour per theme from generated `values/` and `values-night/` colour resources (extend `design/tools/gen_theme.py`; do not type hex by hand). Fade the first screen in (`fadeIn(250)`) when `AppRoot`'s settings arrive. | none | should |

### 2.2 Dialogs and sheets (owner A)

| Where | What | Haptic | Priority |
|---|---|---|---|
| `ui/components/Overlays.kt` DialogCard and ChoiceDialog. Call sites: `ExpenseDetailScreen.kt` (2), `GroupSettingsScreen.kt` (leave, delete), `YouAccountScreen.kt` (delete account, sign out), the rename dialog (`ConfirmAccountsScreen.kt`, Accounts) | **Scrim:** `fadeIn(150, linear)` / `fadeOut(150)`. **Card enter:** `fadeIn(150)` + `slideInVertically { 24dp }` + `scaleIn(0.94, transformOrigin = bottom centre)`, 250 ms emphasized-decelerate. **Card exit:** `fadeOut(100)` + `scaleOut(0.96)`, 150 ms emphasized-accelerate. **Exit mechanism:** call sites switch to `AnimatedOverlay(value)` (3.5), which keeps the last content while it leaves. No callback waits for an animation. Taps and dismiss requests are ignored while it is leaving. Check on the Pixel that the platform dialog window adds no window animation of its own: the Compose `DialogWindowTheme` sets none, so the window inherits the activity theme. | none on open; confirm or reject on the result (2.7) | must |
| `Overlays.kt` DialogChoice pills | Today they show no press at all (`indication = null`). Put the press overlay on the **inner clipped pill**, not on the outer 48 dp touch box. | none | must |
| `Overlays.kt` BottomSheet (friend's link 46/130/131) and `AddAllSheet` in `ReviewPaymentsScreen.kt` | Merge AddAllSheet into BottomSheet, so the motion exists once; B adopts it. **Scrim:** `fadeIn(250, linear)` / `fadeOut(150)`. **Sheet:** `slideInVertically { it }` tween(300, emphasized-decelerate); exit `slideOutVertically { it }` tween(250, emphasized-accelerate). Uses `AnimatedOverlay`. | none | must |
| BottomSheet drag on the drawn grab handle, and predictive back | `anchoredDraggable` with Shown and Hidden anchors: dismiss past 30% of the height or above 1000 dp/s; settle with spring/default. Back becomes `PredictiveBackHandler`: scale 1 to 0.95 and scrim alpha 0.72 to 0.5 with progress. | threshold-in / threshold-out at the dismiss line | nice |
| Platform DatePickerDialog, Credential Manager, share sheet, file and contacts pickers, permission dialogs | System motion. Add nothing. | system | none |

### 2.3 Press: 3D faces and flat surfaces (owner B)

| Where | What | Haptic | Priority |
|---|---|---|---|
| `ui/components/core/Raised.kt` `RaisedSurface`: AppButton Primary and Secondary on every screen, the tappable BalanceCard (Home spent card), the active nav pill | Replace `collectIsPressedAsState` + `animateDpAsState(tween 70)` with a driver (3.3). **Press:** haptic, then `animateTo(4dp, tween(40, linear))`. **Release:** finish the drop, hold until 100 ms after Press, then `animateTo(0, spring/press-release)`. **Cancel** (finger slid off or a scroll took over): `tween(120, standard)`, no hold, no bounce. A quick tap inside a scroll body (Press and Release in the same frame, because foundation 1.12.1 delays Press by the 100 ms tap timeout in scroll containers) still plays one full press. **Drawing:** `graphicsLayer { translationY; shape; clip = true }` in place of `offset{}` + `clip`. **onClick:** never delayed. **New parameters:** `pressSource: InteractionSource?` so a face can press when the clickable sits outside it (nav pill), and `pressHaptic: Boolean = true`. | `haptic/press` on Press, rich phones only. Off for the nav pill and for buttons inside the draggable swipe card. | must |
| `ui/components/AppButton.kt` Ghost (Undo, Cancel, Not now, Split it instead, Open Flat 302, Dismiss) | Ghost buttons have no face or edge, so they show nothing today. While pressed, draw a `color/brand/soft` pill (radius 16, face shape, inside the 5 dp edge space): in over 50 ms, held until 100 ms after Press, out over 150 ms standard. | none | must |
| `ui/components/motion/PressIndication.kt`, provided as `LocalIndication` in `ui/theme/Theme.kt`. It covers rows (`FlatRow.rowClick`, ExpenseRow, BalanceRow, SettingRow), SurfaceCard, InfoRow links, Home stat tiles, You header, TopBar back and action, nav icons, AppChip, GoogleButton, the swipe card's category chip | Today there is no `MaterialTheme`, so these get foundation's `DefaultDebugIndication`: raw black at 30%, not animated, nearly invisible in dark mode, and not a token. Replace it with an `IndicationNodeFactory` that draws `state/pressed` inside the element's existing clip: in over 50 ms, held until 100 ms after Press, out over 150 ms standard; Cancel fades out over 100 ms. No ripple and no scale. | none | must |
| Overlay placement fixes | Each overlay must sit on the visible shape. **OptionCard:** the `selectable` sits on RaisedSurface's outer box, which includes the edge space; pass its interaction source in so the overlay is drawn on the clipped face. **SegmentedToggle:** the `selectable` covers the touch overhang; draw on the clipped label area. **DialogChoice:** the same, on the inner pill. **GoogleButton:** keep Google's branding; the overlay is allowed by Google's state guidance, but check it in both themes. | none | must |
| Disabled to enabled (Split until the parts match, Import until a name is chosen) | Opacity 0.4 to 1 over 150 ms, standard. | none | nice |
| Previews and capture | A preview-only interaction source that emits `PressInteraction.Press`, so pressed states can be captured and compared with Figma. | none | should |

### 2.4 Components (owner B unless noted)

| Where | What | Haptic | Priority |
|---|---|---|---|
| `AppSwitch.kt` | Knob uses spring/fast in place of `tween(120)`. Track, knob and border colours over 150 ms standard. Read progress in the `offset{}` and `drawBehind` lambdas, not in composition (it recomposes every frame today). | toggle-on / toggle-off from the new value, in AppSwitch's own `toggleable` | must (haptic), should (motion) |
| `SettingRow.kt` switch rows (accounts, reminders, Split this, Do this every time, Choose people, New group, Confirm accounts) | The trailing AppSwitch has `onCheckedChange = null`, so the row's `toggleable` plays the haptic. The two never fire for the same tap. | toggle | must |
| `SegmentedToggle.kt` (Home Me/Family) | One raised pill drawn behind both segments. Its x and width (the labels differ) animate with spring/fast and keep the 5 dp edge. Label colours over 150 ms. | selection, inside the existing `if (!selected)` guard | must |
| `AppChip.kt` (filters, Equally/Amounts/%/Shares, categories, scope, Paid by/with) | Fill, label and outline over 150 ms standard; overlay on press; no scale. Activity: `animateScrollToItem` keeps the chosen chip in view (nice). | selection when the selection changes; not for `Role.Button` chips such as "+ New" | should |
| `OptionCard.kt` (How far back, Tracking start, Same split?, Mark as paid, Settle up, "Which one is you?") | Face, border and edge alpha over 150 ms. The radio dot scales 0 to 1 with spring/pop; ring to fill over 150 ms. The face does not rise as well (one motion per change). | selection when the selection changes | should |
| `payment/ChooseCategory.kt` row pick | Overlay. | selection | should |
| `AppInput.kt`, `payment/AddExpenseScreen.kt` AmountField | Focus border colour over 150 ms (width snaps). Error border to `color/status/negative` over 150 ms. The helper text and the Amount error row use `Reveal` (2.5). | (2.7) | should |
| `LoadingPlaceholders.kt` (owner A): Groups, Group page and Join group loading | Pulse alpha 1 to 0.55, tween(900, standard), reverse, infinite. One `rememberInfiniteTransition` per screen so the bars pulse together, applied in `graphicsLayer { alpha }`. Placeholders are in the tree at once (so semantics and tests see "Loading groups") and fade in with `tween(150, delayMillis = 200)`. Static when motion is reduced, and still shown only after 200 ms, counted on the frame clock (3.11), so quick data never flashes them. | none | should |
| `ProgressBar.kt` (owner A): Scanning, Reading older payments, Importing | Fill uses `animateFloatAsState(tween(250, standard))` drawn in `drawBehind`; it snaps when the value goes down. The "20 of 46" text stays plain text. | none | should |
| `Banner.kt` call sites (owner A): GroupPageCommon, SettleWidgets, RemindScreen, ImportSplitwiseScreen, SignInNameScreen, YouAccountScreen, JoinGroupScreen, Home offline | `Reveal` from the top. Joined to Undone: a 150 ms crossfade. The `liveRegion` stays on the stable outer node. | (2.7) | should |

### 2.5 Content changes (owner A, except AddExpenseScreen, which is B's)

| Where | What | Haptic | Priority |
|---|---|---|---|
| LazyColumns: `home/HomeScreen.kt`, `activity/ActivityRows.kt` and `ActivityScreen.kt`, `month/MonthScreen.kt`, `groups/GroupsScreen.kt` | `Modifier.animateItem(fadeInSpec = tween(150), placementSpec = spring(0.9, 700, IntOffset.VisibilityThreshold), fadeOutSpec = tween(150))` on every keyed item. Home's detected card is keyed `"detected"` today, so a new payment does not animate: **key it by payment id.** In Groups, placeholders to content then crossfades with no extra code. | none | must (Home, Activity), should (rest) |
| Lists built on a Column: GroupPageScreen (expenses, balances, Not saved rows), GroupSettingsScreen (people), ScanningScreen (found accounts), ConfirmAccountsScreen, ChoosePeopleScreen | Rows added after the first show use `Reveal`; rows present on first show do not animate. | none | should |
| Home Me/Family: BalanceCard content | `FadeThroughContent` keyed by view, with no size animation. The Latest list follows through `animateItem`. | selection (from the toggle) | must |
| Month: Previous month and Next month | `AnimatedContent` keyed by month: next slides in from +30 dp, previous from -30 dp; fade out 90, in 160; 250 ms emphasized. | none | should |
| Form sections: AddExpenseScreen (B: Split this, Paid with, scope, category), SplitScreen (exact-amount inputs, "what is left" note) | Use `Reveal`. Never key an `AnimatedContent` by typed text (see 3.8). | none | should |
| Whole-state swaps: ExpenseDetail (edit mode), ImportSplitwise states, Join loading to ready, Group page loading to shown | `FadeThroughContent` keyed by the state class. | none | should |
| Top bar action Edit/Cancel; footer label "Add to November"/"Next" | Crossfade over 150 ms. | none | nice |
| GroupPage, when a result banner appears | `animateScrollTo(0)` in place of `scrollTo(0)`; a jump when motion is reduced. | none | nice |
| Screens that draw an empty frame while leaving (GroupPage `Gone`, Review `NothingWaiting`) | They would slide out empty. Keep drawing the last shown state while the entry is leaving. | none | should |
| Welcome sample rows (first launch) | Rise in: 16 dp + fade, 250 ms emphasized-decelerate, 60 ms apart, once. | none | nice |

### 2.6 Review swipe (owner B; `review/ReviewPaymentsScreen.kt`, `review/SwipeCard.kt`)

| Where | What | Haptic | Priority |
|---|---|---|---|
| Next card | When `card.id` moves forward, one `Animatable` takes the stack from 1 to 0 with spring/default. **New top card:** scaleX from 0.927 (24 dp narrower) to 1 and translationY from 12 dp to 0. **Cards behind:** at depth + progress. **Newly revealed third card:** alpha 0 to 1 over 150 ms. | none | must |
| Add or Ignore buttons; TalkBack actions | Fly-out uses `tween(250, emphasized-accelerate)` to ±1.5 widths, tilting to ±12°. Today's `tween(200)` with default FastOutSlowIn slows at the end, which is wrong for an exit. (Changed after review: the decision is sent at once, rule 3.8; the next card takes the top while the decided one flies out over it, drawing only, and Undo during the fly-out brings it back from where it is.) | press only | must |
| Drag released past the threshold | The card keeps the release velocity; duration = remaining distance / velocity, clamped to 120-250 ms, linear. | threshold-in on release if it never armed | must |
| Drag released under the threshold | `spring/default` with the release velocity as `initialVelocity`. | none | must |
| Commit line (`stackWidth * DecideFraction`) | Add an `armed` flag to `SwipeState`: arm when |offset| reaches the line, disarm when back under (line - 8 dp). The stamp pops at the arm point (scale 1 to 1.08 to 1, spring/pop), so the haptic matches something on screen. | threshold-in, threshold-out; ticks also play when motion is reduced | must |
| Stamp first shows (at 24 dp, as today) | Alpha 0 to 1 over 100 ms, no scale (the one pop is at the commit line). | none | should |
| Card border colour (subtle, brand, negative) | Over 150 ms. | none | should |
| Undo | The card flies back from the side it left (±1.5 widths to 0, ±12° to 0, tween(300, emphasized-decelerate)) while the stack shifts back. This needs the last decision's side to be remembered in `ReviewingContent`. | none | should |
| "3 of 14" | No animation: it is a `liveRegion`, and swapping it would announce twice. | none | cut |
| Reviewing to Sorted | `FadeThroughContent`, 250 ms. Sorted follows the success pattern (2.7). | confirm once | must |
| Remove animations | As `docs/product.md` says: no fling and no tilt; the next card replaces the old one. Haptics still play. | yes | must |

### 2.7 Feedback, success and errors (owner B)

| Where | What | Haptic | Priority |
|---|---|---|---|
| Success screens: `ExpenseAddedScreen`, `SplitAddedScreen`, `BalanceClearedScreen`, `GroupImportedScreen`, Review Sorted | On top of the Result transition (2.1), at +100 ms the StickerTile pops: scale 0.5 to 1 with spring/pop, and alpha 0 to 1 over 150 ms. It plays once per arrival (`rememberSaveable` flag), so it does not replay after recreation or process restore. Footer buttons are tappable at once. | confirm at the start of the pop, once per arrival | must |
| Group created (`GroupsNav.kt`), joined (`JoinNav.kt` `OpenGroup`), Reminder sent (`GroupPageNav.kt` effect), dialog action done (delete expense, leave or delete group, sign out, delete account) | No extra motion: the next screen or banner carries it. | confirm, through the activity root view | should |
| Refused submit: Add expense amount | Shake the amount row: `translationX` keyframes 0, -8, 8, -6, 6, -3, 3, 0 dp over 300 ms in `graphicsLayer`, together with the error message. No shake when motion is reduced; the message and the haptic stay. `AddExpenseViewModel` needs a `refusedSubmits: Int` counter: a second refused tap does not change `amountError`, so a Boolean cannot trigger again. | reject | must |
| Refused submit, other fields: AddFriend phone, ProfileFields (name, UPI ID, phone on Your name and Account), NeedsReview amount, ExpenseDetail edit amount, Group name | The same `Modifier.shakeOn(count)` with a counter in each ViewModel. | reject | should |
| A Problem or Offline banner, or "No reminder sent", shown as the direct result of a tap | `Reveal` (2.4). | reject | should |
| Scan finished with accounts found; "Link copied" banner (Android 12 and older only) | none | confirm | nice |
| Split's live "parts do not add up" note; disabled buttons | none | none | none |

## 3. How to build it

### 3.1 Shared helpers

| File (new unless noted) | Owner | Contents |
|---|---|---|
| `ui/theme/Motion.kt` | Phase 0 | `object Motion` (durations, easings, springs, distances from section 1). `LocalReducedMotion`. `rememberReducedMotion()` moved here from `review/SwipeCard.kt` and made public. `@Composable fun <T> motion(spec): FiniteAnimationSpec<T>`, which returns `snap()` when reduced. |
| `ui/theme/Haptics.kt` | Phase 0, then B | `interface AppHaptics { press(); selection(); toggle(on); thresholdIn(); thresholdOut(); confirm(); reject() }`. `LocalAppHaptics = staticCompositionLocalOf<AppHaptics> { NoHaptics }`. `HapticPolicy`: pure Kotlin with an injected clock and player, holding the 80 ms tick throttle and the 250 ms confirm gap. `ViewHaptics(view, rich, policy)`. `hasRichHaptics(context)`. |
| `ui/components/motion/PressDrop.kt` | B | `rememberPressDrop(source, enabled, haptic): State<Float>` (0 to 1). `PressTiming`, a pure function: hold left = max(0, pressAt + 100 - now). |
| `ui/components/motion/PressIndication.kt` | B | `PressIndication` and `GhostPressIndication` (`IndicationNodeFactory` + `DrawModifierNode`). |
| `ui/components/motion/Shake.kt` | B | `Modifier.shakeOn(count: Int)`, which also calls `haptics.reject()`. |
| `ui/components/motion/SuccessEntrance.kt` | B | `ConfirmOnArrival()` and `Modifier.stickerPop(...)` with a saveable played flag. |
| `ui/components/motion/Reveal.kt` | A | `Reveal(visible)`: `AnimatedVisibility`. **Enter:** `expandVertically(spring/default, expandFrom = Top)` + `fadeIn(150, delay 50)`. **Exit:** `shrinkVertically(150, emphasized-accelerate)` + `fadeOut(100)`. It does not animate on first composition. |
| `ui/components/motion/FadeThroughContent.kt` | A | An `AnimatedContent` wrapper: out 90, in 160 with delay 90, `SizeTransform` null. It hides the leaving content from accessibility (`semantics { hideFromAccessibility() }` while `transition.targetState != EnterExitState.Visible`). |
| `ui/components/motion/AnimatedOverlay.kt` | A | `AnimatedOverlay(value: T?) { scope, last -> }`. It remembers the last non-null value and a `MutableTransitionState`, keeps the overlay (or the `Dialog` window) composed until the exit ends, and exposes `isLeaving` to guard taps and dismiss requests. |
| `ui/nav/ScreenTransitions.kt` | A | `enum ScreenKind { Tab, Push, Modal, Result }`. `NavDestination.kind()` (by `hasRoute<T>()`; the default is Push). A pure decision table `move(from, to, pop): Move`. `Move` mapped to Compose transitions in one place. Predictive back specs. |

### 3.2 Navigation wiring

Pass all six lambdas to `NavHost` in `AppNavHost.kt`. Each one reads `initialState.destination.kind()` and
`targetState.destination.kind()`, and returns None when `LocalReducedMotion` is true (read via `rememberUpdatedState`).

```kotlin
NavHost(navController, startDestination, modifier,
    enterTransition = { transitions.enter(initialState, targetState) },
    exitTransition = { transitions.exit(initialState, targetState) },
    popEnterTransition = { transitions.popEnter(initialState, targetState) },
    popExitTransition = { transitions.popExit(initialState, targetState) },
    predictivePopEnterTransition = { edge -> transitions.predictiveEnter(initialState, targetState, edge) },
    predictivePopExitTransition = { edge -> transitions.predictiveExit(initialState, targetState, edge) },
) { ... }
```

- Remove the four `None` overrides in `mainTab()`. The table returns None for Tab to Tab instead.
- Adding a route now means adding it to `ScreenKind` when it is not a plain push. Add this to `docs/architecture.md`.
- `rememberNavigateOnce` ignores taps until the new entry is RESUMED, which is after its enter transition. Keeping
  pushes at 300 ms or less keeps that window short.

### 3.3 RaisedSurface press driver

```kotlin
val drop = remember { Animatable(0f) }
LaunchedEffect(source, canPress) {
    if (!canPress) { drop.snapTo(0f); return@LaunchedEffect }
    var pressedAt = 0L
    source.interactions.collectLatest { i ->
        when (i) {
            is PressInteraction.Press -> { pressedAt = frameMillis(); if (pressHaptic) haptics.press(); drop.animateTo(1f, Motion.pressDown) }
            is PressInteraction.Release -> { drop.animateTo(1f, Motion.pressDown); holdUntil(pressedAt + Motion.PressHoldMillis); drop.animateTo(0f, Motion.pressRelease) }
            is PressInteraction.Cancel -> drop.animateTo(0f, Motion.pressCancel)
        }
    }
}
// face: Modifier.graphicsLayer { translationY = drop.value * PressDepth.toPx(); shape = faceShape; clip = true }
```

- `holdUntil` waits on `withFrameMillis`, not `delay()`. It then runs on the same clock as the animations, including the
  Compose test `mainClock`.
- When motion is reduced the hold stays: the face shows pressed for 100 ms with no movement in between. This is state,
  not motion.
- `canPress` = enabled and has an edge and (has `onClick` or `pressSource != null`).
- TalkBack double-tap sends no Press, so it plays no motion and no haptic; TalkBack gives its own feedback.

### 3.4 Haptics helper

- **Playback.** `ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.X)` with no flags. It follows the
  system Touch feedback setting, needs no VIBRATE permission and applies the minSdk 26 fallbacks.
- **Why not Compose's `LocalHapticFeedback`.** Compose UI 1.12.1 `HapticFeedbackType` has no
  `GESTURE_THRESHOLD_DEACTIVATE`, and `HapticFeedbackType(24)` plays nothing.
- **Which view.** Always use the activity root view: `AppRoot` captures `LocalView.current` and provides `ViewHaptics`.
  A dialog's own view detaches when it closes, and a confirm played through it after that is lost.
- **Rich-haptics check** (press only). `hasRichHaptics` = API 30+, `hasVibrator()` and
  `areAllPrimitivesSupported(PRIMITIVE_CLICK, PRIMITIVE_TICK)`, via `VibratorManager.defaultVibrator` on API 31+.
  Cheap phones whose motors buzz get no press haptic. All the other haptics are not gated, so the app feels like the
  phone's own system UI.
- **What not to use.** Never `Vibrator.vibrate`, `createOneShot` or `FLAG_IGNORE_*`.
- **Previews and tests.** They get `NoHaptics` by default.
- **Tuning.** If `VIRTUAL_KEY` feels too strong on a phone, swap it for `KEYBOARD_TAP` in one place.

### 3.5 Overlays

Call sites change from `x?.let { ChoiceDialog(...) }` to `AnimatedOverlay(x) { ChoiceDialog(...) }`.

- `DialogCard` and `BottomSheet` stay callable on their own, with an enter animation only. `OnRaisedSurfaceTest`
  renders them directly.
- Inside the dialog window, the scrim and the card each use `AnimatedVisibility(visibleState = scope.state)`.
- A refused Save in the rename dialog keeps the dialog open, because the caller's state still holds it. This is why the
  exit is driven by state and never by a delayed callback.

### 3.6 Tab content and pill

- `AppRoot` provides `LocalTabMemory` (`var last: MainTab?`).
- `MainScaffold` reads `from = remember { memory.last }` on first composition and sets `memory.last = active` in a
  `SideEffect`.
- The content animates in only when `from != null && from != active`, that is, when the tab was reached from another
  tab. Coming back from a pushed screen, the NavHost pop transition handles it.
- `BottomNav(active, from)` drives the pill from `from` to `active` with
  `updateTransition(MutableTransitionState(from ?: active))`.
- The dock is never inside the animated layer.
- This keeps one dock per tab, so the screen revealed by a back gesture from a pushed screen still shows its dock.
- Changed after the device check (blank canvas for about 100 ms on a tab tap, and on Back between tabs, while the new
  tab was composed and read its data): tab to tab is `Move.Tab`. The old tab's exit keeps it drawn, unchanged, for
  `motion/duration/cap`. The new tab's page (the canvas and the content, one layer) waits until its content draws
  something (at most 120 ms), then fades in over the old tab (150 ms) as the content grows from 96%. A pop (Back, or a
  tap on Home) draws the new tab under the old one, so the old page fades out over it instead. The pill is one motion
  in `TabMemory`, shared by both docks, and a tap starts it at once. A tab navigates while it is on top of the back
  stack (`LocalMayNavigate`), since the NavHost resumes it only when the old tab is gone (3.8). Remove animations swaps
  the tabs at once.

### 3.7 Remove animations (`LocalReducedMotion`, observed live from `ANIMATOR_DURATION_SCALE == 0`)

Compose already ends `tween` and `spring` animations on the next frame. These need explicit handling:
1. NavHost, dialog and sheet transitions: None or `snap()`.
2. Loops (placeholder pulse): static.
3. Gesture transforms (review tilt and fling, sheet fling): off. The drag still tracks, and its threshold haptics still
   play.
4. Shake: skipped; the message and the haptic stay.
5. Anything timed outside an animation spec. Use `tween(delayMillis = ...)` for delays: those are scaled, so they
   vanish at scale 0. The press hold uses the frame clock and stays (3.3), and so does the 200 ms wait before loading
   placeholders show (3.11): a wait is not motion.
6. Haptics: never affected. They follow the Touch feedback setting only.

### 3.8 Rules for every change

- Animate transforms and alpha in `graphicsLayer{}`, `offset{}` or `drawBehind{}` lambdas. Never read an animated value
  in composition.
- Size animations (`expandVertically`, `animateContentSize`) only on small sections, never on whole screens or long
  lists.
- Fades: only one full-screen translucent layer at a time, with fade windows of 100 to 160 ms.
- Loops only through `rememberInfiniteTransition`. The Compose test rule cancels these when `autoAdvance` is on, which
  was checked in ui-test 1.12.1; a hand-written `while (true) animateTo` would hang `waitForIdle`.
- Never key an `AnimatedContent` by text being typed. That rebuilds `AppInput`/`TypedText` and closes the keyboard. Key
  by mode or state class.
- Never put a `liveRegion` node inside `AnimatedContent`. Hide leaving content from accessibility.
- Never delay an `onClick` or a callback behind an animation.
- No Lottie, no shimmer gradient, no blur or shadow animation, no shared-element framework.

### 3.9 Predictive back settle (`ui/nav/SpringBack.kt`, 2026-11-22)

Owner rule 2 says a back swipe follows the finger, then settles with a spring from where it was released. The NavHost
alone does not do the second half. What navigation-compose 2.10.2 does (read in its bytecode, `NavHostKt` and
`NavHostEventHandler`):
- While the finger moves, it seeks its `SeekableTransitionState` to each back event's progress
  (`seekTo(progress, previousEntry)`), so the predictive specs (linear tracks) follow the finger.
- On commit it pops, then calls `transitionState.animateTo(entry)` with no spec. animation-core 1.12.1 then moves the
  fraction linearly over the rest of the transition's duration, so each screen glides on at the speed of its 250 ms
  linear track and stops dead. There is no parameter for this spec.
- On cancel it runs `animate(fraction, 0, tween(fraction x duration))` and snaps back. On Android 16 this is never
  seen: the system springs the progress back to 0 (events keep coming after the finger lifts) before it cancels.
- Android 16 sends no progress after the finger lifts on a commit: it calls completed at once.

So `SpringBack` wraps the NavHost (public API only: navigationevent 1.1.2's `NavigationEventDispatcher`,
`NavigationEventInput` and `LocalNavigationEventDispatcherOwner`; no fork). The NavHost and every `BackHandler` inside
it register with its own dispatcher. Its one handler on the activity's dispatcher is enabled exactly while one of
theirs is, and passes each event on. When a swipe that moved the screens is committed, and the NavHost takes it
(the handler on top is the NavHost's, `NavBackStackEntryInfo`) and shows a preview (`Move.previewsBack`: Back,
FadeThrough, ModalDrop), it springs the progress to 1 with `motion/spring/screen` from the finger's speed (the last
100 ms of events, capped at 5 per second), sends each frame as a progress event, then completes. The NavHost then has
nothing left to play. Everything else is passed on at once: a Back button (Android 16 starts a swipe at 0 and
completes it at once; nothing moved, so the NavHost plays its pop), a screen's own `BackHandler`, tab to tab (no
preview) and Remove animations. A settle still running ends at once when a new swipe starts or the app pauses.

Measured on emulator-5554 (`build/keep/motion-032/a-predictive-back/`): before, the last frames before the end move
at about 4 progress per second (the linear track's speed) and then stop. After, the relay's frames go 0.37 at the
finger's 0.7/s, up to 4.3/s, then 3.0, 2.1, 1.3, 0.5, 0.2, 0.07/s and rest at 1, about 300 ms to 0.995. Tab to tab,
cancel and Back button behave as before.

### 3.10 No blank frames while a pushed screen loads (2026-11-22)

Measured on emulator-5554 (`build/keep/motion-032/b-blank-frames/`, share of each frame that is not canvas; a page is
40 to 70%, a near-blank frame under 5%):
- Push to Review: 6 near-blank frames, about 150 ms. Push to a Group page: 8, about 230 ms. Push to Month, to an
  expense and the Expense added result: none, or a single dim first frame.
- Cause: Review, the Group page and Join group start in a loading state (the ViewModel's first value) that shows only
  the page's frame, since placeholders wait 200 ms. Their data came about 100 ms later, and the state swap is a
  `FadeThroughContent`: the frame (title, back button) faded out over 90 ms and the content faded in only after that,
  so the screen faded to nothing in the middle of its push. This happens on every phone, fast or slow, because the
  fade-through has fixed timings.
- Fix: `FadeThroughContent(isLoading = ...)`. A loading state that the data replaces before
  `motion/delay/placeholder` (200 ms) is replaced at once; the screen's own push carries the motion. A loading state
  that stayed long enough to show its placeholders still fades through to the content, as the design draws it.
- After: Review none; Group page one dim first frame. That frame is the first transition frame after the new screen's
  first composition (about 100 ms on the software emulator, `gfxinfo` framestats), before its data is read: the
  incoming page, still empty, fades in over the old one. It is not specific to any screen and is one frame long here.

### 3.11 Loading with Remove animations on (2026-11-22)

What the code did: `rememberPlaceholderMotion` showed placeholders at once when motion was reduced. Their 200 ms wait
was the delay of the fade-in tween, and Compose scales tween delays by the animator duration scale, so at 0 it was
gone. Measured on emulator-5554 with all three scales at 0 (`build/keep/motion-032/c-reduced-loading/`): a push to a
Group page drew the grey placeholders for about 65 ms (2 frames) before its data, a flash of the loading state.

Fix: with motion reduced, placeholders wait the same 200 ms on the frame clock (`awaitFrameMillis(scaled = false)`),
then show at once and stay still. They are in the tree from the start, so screen readers still hear "Loading groups".
After: the push draws the page's frame for those 2 frames and then the data; no placeholders.

Still open: the first visit to the Groups tab (and to any tab not visited yet) with Remove animations on draws the tab
with nothing but its dock for about 2 frames (85 ms here). That is not the loading state: the tab's ViewModel has no
value yet (`state` is null until its first database read), so the route draws nothing. With motion on, `MainScaffold`
hides it by keeping the old tab drawn until the new one has content (3.6). With Remove animations on that is not
possible through the NavHost: at scale 0 every transition, and a delay inside it, ends on the next frame, so the old tab
is gone at once. Fixing it means giving a tab its first state at once (for Groups, a value of
`GroupsRepository.observeHome()` kept warm for the app, read synchronously as the ViewModel's first state), which is a
data change, not a motion change.

## 4. How the design records it

- **Motion section in Figma.** Add a "Motion" section on the Figma Design System page, on both boards, with:
  - the token tables of section 1, including the Figma spring equivalents and the haptic tokens
  - one card per pattern: push (shared axis), modal rise, result arrival, tab content plus pill move, predictive back,
    dialog, sheet, 3D press (rest, then 4 dp down with a 1 dp lip), Ghost pressed, flat pressed overlay, switch knob,
    segmented pill slide, option radio pop, review stack and commit-line stamp pop, sticker pop, shake, placeholder
    pulse, Reveal
  - the Remove animations behaviour
- **Motion tokens as variables.** Optionally add the tokens as Figma variables (a "Motion" collection: durations as
  numbers, easings as strings). Extend `design/tools/dump.js` so they export into `tokens.json` under `motion`.
- **Export.** `design/spec/motion.md` (and `motion.png`) is exported from that section by `dump.js`, and this file links
  to it.
- **Pressed states as component variants.** The app will show these, so per CLAUDE.md they are drawn on the Design
  System page:
  - Button `State=Default|Pressed`. Primary and Secondary pressed: face y+4 with a 1 dp edge left. Ghost pressed: the
    `color/brand/soft` pill.
  - The flat pressed overlay on one Expense Row, one Chip, one dialog choice pill and one unselected Option Card.
  - The Swipe Card stamp at the commit line.
- **Screens and flow.** No screen frame changes, and `flow.md` is unchanged: no screens, states or branches are added.
- **Component notes.** Add a "Motion and haptics" note to: `Button.md`, `Switch.md` (replacing "animation is not
  defined"), `Chip.md`, `Segmented-Toggle.md`, `Option-Card.md`, `Swipe-Card.md`, `Bottom-Nav.md`, `Input.md`,
  `Balance-Card.md`, `Setting-Row.md`, `Expense-Row.md`, `Balance-Row.md`, `Info-Row.md` and `Top-Bar.md`.
- **README.** Add a dated "Motion added" section to `design/spec/README.md`, then re-export the JSON and both PNGs of
  the changed components.
- **Docs.**
  - `docs/architecture.md`: replace "switches tabs without animation" and add the `ScreenKind` rule.
  - `docs/product.md`, one line: "Remove animations turns all motion off app-wide; the next review card replaces the
    last; haptics follow the system Touch feedback setting."
- **Owner approval.** The Ghost pill and the 10% flat overlay are new looks. The owner approved both from before and
  after pictures in both themes (2026-11-20). The 3D press is the design's own intent.

## 5. Tests

### 5.1 Add

| Test | Kind | Checks |
|---|---|---|
| `PressTimingTest` | JVM | the hold is 100 ms for a same-frame tap and 0 for a long press |
| `HapticPolicyTest` | JVM, fake clock and player | ticks inside 80 ms are dropped; confirm and reject are never dropped; confirm comes at least 250 ms after a press |
| `ScreenTransitionsTest` | JVM | every route maps to the right `ScreenKind`; each (from, to, pop) pair gives the expected `Move`; reduced motion always gives None |
| `PressDropTest` | Robolectric, `autoAdvance = false` | a same-frame tap moves the face down 4 dp (the `positionInRoot` of a tagged face) and back by about 300 ms; Cancel returns with no hold; a disabled button never moves |
| `HapticsWiringTest` | Robolectric, a recording `AppHaptics` | switch row gives `toggle(true)`; chip change gives `selection()`; tapping the chip that is already chosen, or "+ New", gives nothing; an invalid Add gives `reject()` |
| Review drag test | Robolectric | past 35%, then back, gives thresholdIn then thresholdOut, with no chatter inside the 8 dp hysteresis |
| Success arrival test | Robolectric, `StateRestorationTester` | ExpenseAdded gives `confirm()` exactly once, also across recreation |
| `AnimatedOverlayTest` | Robolectric | the last content stays during the exit and taps are ignored; reduced motion removes it in the same frame; a rename Save refused by state keeps the dialog |
| `TabMemoryTest` | Robolectric | content animates only when arriving from another tab |
| Placeholders under reduced motion | Robolectric | the pulse is static, "Loading groups" exists at once, and they show only after 200 ms (`PlaceholderMotionTest`) |

### 5.2 Adjust or keep green

- Navigation tests (`SplitNavigationTest`, `NewGroupNavigationTest`, `SplitwiseNavigationTest`) build their own
  `NavHost`. They are unaffected.
- `ReviewPaymentsAccessibilityTest.undoWhileTheCardFliesOut...` advances 50 ms, then 1000 ms. The decision is now sent
  at once (2.6); with the state unchanged, as in that test, Undo stays ignored until the next card shows.
- `ReviewPaymentsScreen` keeps its `reduceMotion` parameter. Only its default changes to `LocalReducedMotion.current`.
- `GroupsScreenTest.loadingShowsPlaceholders...` asserts right after `setContent`. The 200 ms delay must be a fade-in
  of a node that is already in the tree, not a delayed composition.
- `OnRaisedSurfaceTest` renders `DialogCard` and `BottomSheet` directly. Keep them callable without `AnimatedOverlay`.
- `ButtonSizeTest` measures the bounds at rest. It is unchanged, because the press only translates the layer.
- Tests that click and then assert at once (Groups, SettleUp, Rename) stay valid only if no callback waits for an
  animation (rule 3.8).
- ViewModel tests that compare a whole state after a refused submit need the new `refusedSubmits` value (Add expense
  and the others in 2.7).

### 5.3 Capture and device checks

- **Capture tool.** `design/tools/capture.sh` screenshots after 2 s. `PreviewActivity` should provide
  `LocalReducedMotion = true` by default, so captures are deterministic (no pulse phase, no half-finished pop). An extra
  `--ez motion true` shows the motion. Previews get `NoHaptics`.
- **On a phone:**
  - every push, pop, modal, result, tab, dialog and sheet
  - a quick tap on a button inside Home's list
  - a back gesture from a pushed screen (the tab underneath shows its dock)
  - Remove animations on: nothing moves, the press still shows for 100 ms, haptics still play
  - Touch feedback off: no haptics
  - `adb shell dumpsys vibrator_manager`: check the throttle and the confirm gap
- **On a 2-3 GB phone or a low-end emulator profile:** `adb shell dumpsys gfxinfo <app> framestats` on push, tab switch,
  Activity filter change and Review. No frame budget may be missed more than occasionally.

## 6. Build order for two engineers

**Can it run alongside other work?** It touches only UI files, plus one counter field in a few ViewModels. It can run
at the same time as other work only if those streams do not edit the files listed below. Otherwise it waits until they
are done (the owner's condition).

### Phase 0: contract

One engineer (A), about half a day; B reviews.
1. The owner checks the phone's settings (section 0).
2. Create:
   - `ui/theme/Motion.kt`
   - `ui/theme/Haptics.kt` (interface, `NoHaptics`, `ViewHaptics`, `HapticPolicy`)
   - `LocalReducedMotion` provided in `ui/theme/Theme.kt`
   - `LocalAppHaptics` provided in `ui/shell/AppRoot.kt`
   - `rememberReducedMotion` moved out of `review/SwipeCard.kt`
   - the `PreviewActivity` default
   - this file
3. After merge, ownership:

| Files | Owner |
|---|---|
| `Theme.kt`, `Haptics.kt`, `SwipeCard.kt` | B |
| `AppRoot.kt` | A |
| `Motion.kt` | shared; changes by review only |

### Phases 1 to 6, in parallel

Engineer A works on navigation, shell, overlays and content. Engineer B works on press, controls, haptics, review and
success screens. Each row is ordered by what the owner notices first.

| Step | Engineer A (owns these files) | Engineer B (owns these files) |
|---|---|---|
| 1 | **Screen transitions:** `ui/nav/ScreenTransitions.kt`, `AppNavHost.kt`, `Navigation.kt`, `SignInGate.kt`, `signin/SignInNav.kt` | **Press:** `core/Raised.kt`, `motion/PressDrop.kt`, `motion/PressIndication.kt`, `Theme.kt` (LocalIndication), `AppButton.kt` (Ghost). Lands the `pressSource` parameter early for A. |
| 2 | **Tabs:** `shell/MainScaffold.kt`, `AppRoot.kt` (TabMemory, first-screen fade), `BottomNav.kt` (pill; wires `pressSource` after B1) | **Controls:** `AppSwitch.kt`, `SettingRow.kt`, `AppChip.kt`, `SegmentedToggle.kt`, `OptionCard.kt`, `AppInput.kt`, `payment/ChooseCategory.kt` |
| 3 | **Overlays:** `Overlays.kt` (DialogChoice indication, BottomSheet merge API), `motion/AnimatedOverlay.kt`; call sites `ExpenseDetailScreen.kt`, `GroupSettingsScreen.kt`, `YouAccountScreen.kt`, `ConfirmAccountsScreen.kt`, `AccountsScreen.kt`. Lands `Reveal.kt` and `FadeThroughContent.kt` early for B. | **Review:** `ReviewPaymentsScreen.kt`, `SwipeCard.kt`. Adopts BottomSheet for Add all after A3. |
| 4 | **Lists and content:** `HomeScreen.kt`, `ActivityScreen.kt`, `ActivityRows.kt`, `MonthScreen.kt`, `GroupsScreen.kt`, `GroupPageScreen.kt`, `GroupPageCommon.kt`, `ChoosePeopleScreen.kt`, `ScanningScreen.kt`, `WelcomeScreen.kt` | **Success and outcomes:** `motion/SuccessEntrance.kt`, `Patterns.kt` (StickerTile), `ExpenseAddedScreen.kt`, `SplitAddedScreen.kt`, `BalanceClearedScreen.kt`, `GroupImportedScreen.kt`, `GroupsNav.kt`, `JoinNav.kt`, `GroupPageNav.kt`, `YouNav.kt`, `PaymentNav.kt` (confirm and reject wiring) |
| 5 | **Banners, loading, progress, state swaps:** `LoadingPlaceholders.kt`, `ProgressBar.kt`, `SettleWidgets.kt`, `RemindScreen.kt`, `ImportSplitwiseScreen.kt`, `SignInNameScreen.kt`, `JoinGroupScreen.kt`, `SplitScreen.kt`. ExpenseDetail edit swap and amount shake use B's `Shake.kt`. | **Refusals:** `motion/Shake.kt` (lands early for A), `AddExpenseScreen.kt` and `AddExpenseViewModel.kt`, `AddFriendScreen.kt` and its ViewModel, `signin/ProfileFields.kt` with the SignInName and YouAccount ViewModels, `NeedsReviewScreen.kt` and its ViewModel |
| 6 | **Design and docs:** `res/values*/` window colours via `gen_theme.py`, `themes.xml`, `docs/architecture.md`, `docs/product.md`, `design/spec/motion.md`, the Figma Motion section, the final `dump.js` export (run once, by A) | **Design:** Figma pressed variants (Button, overlay examples, Swipe Card stamp), the component `.md` notes, the before and after captures for the owner |
| 7 (optional) | Baseline Profile: a `:baselineprofile` module (androidx.baselineprofile + profileinstaller, free), generated on the `xpense_pixel` AVD | Measure on low-end hardware; tune haptic strength on the Pixel |

### Hand-offs

Each helper lands first in its own small pull request:
- B1's `pressSource` before A2 wires the pill
- A3's `AnimatedOverlay` and BottomSheet before B3 adopts Add all
- A3's `Reveal` before B5 uses it in AddExpense
- B5's `Shake.kt` before A5 uses it in ExpenseDetail

No file appears in both columns.

## 7. What was cut, and why

| Cut | Why |
|---|---|
| Hoisting one BottomNav above the NavHost (motion research) | A back gesture from a pushed screen would reveal a tab with no dock until the gesture commits, and the change needs padding plumbing. Keeping a dock per tab, with in-scaffold content motion and a pill that moves from the previous tab, gives the same tab feel. The cost: no back-gesture preview between two tabs, which is the same as today. |
| Shared-element transitions, Lottie, confetti, blur, shimmer gradient | Frame cost on low-end phones, size, and a generic look. |
| Counting or rolling numbers (amounts, "3 of 14", stat tiles) | Money must read at once, and swapping `liveRegion` text announces it twice in TalkBack. |
| Staggered rise of the text on success screens | A third motion on top of the Result transition and the sticker pop. One hero motion is enough. |
| Ring burst behind the sticker | Extra polish that would need new Figma work. Revisit later. |
| Scale-down on press for chips, the back button and nav icons | Two feedbacks on one press; the overlay is enough. |
| OptionCard face rising onto its edge when selected | Stacked with the colour change and the radio pop. Kept the colour and the radio pop. |
| Haptic and nudge on a tap of a disabled button | Disabled controls must stay inert and be announced as disabled. Doing this would mean making them clickable. |
| Predictive back inside dialogs | The Compose `Dialog` handles Back itself. The exit animation on dismiss is enough. |
| Indeterminate sweep on the progress bar | It changes the design ("the bar is empty until counted"). Only if the owner approves it in Figma first. |
| Looping drift of the scanning icon | Loop cost and non-deterministic captures. The progress bar already shows the scan is alive. |
| Haptics on drag start, every card, navigation, tabs, scrolling, opening overlays, Undo, Cancel | Android guidance: less is more. Frequent haptics make people turn Touch feedback off for the whole phone. |
| Gating every haptic on rich motors | Only the high-frequency press is gated. Semantic constants follow the phone's own tuning. |
| Pull-to-refresh or long-press actions added only so there is a haptic to play | Not in the design. |

## 8. What the review changed

### 8.1 Gaps neither research covered (now in the plan)

1. **Active nav pill never presses today.** `BottomNav` passes no `onClick` to `RaisedSurface` (the clickable is the
   outer `selectable`), so `canPress` is false. Its inner Row also draws an overlay, which would double up with the face
   drop. Fixed with `pressSource` and by removing the inner overlay.
2. **Overlays outside the visible shape.** On OptionCard, SegmentedToggle and DialogChoice the clickable sits outside
   the clip (edge space or touch overhang), so an overlay would be a rectangle. It must be drawn on the face, label or
   pill.
3. **Home's detected payment is keyed by a constant** (`"detected"`), so a new payment would not animate. Key it by
   payment id.
4. **Fades that stack.** List fade-ins on first data stack on top of screen fades. `animateItem` does not animate the
   first layout. Only Groups' placeholders-to-content uses it, on purpose.
5. **Test that would break.** `GroupsScreenTest` asserts the placeholder right away, so the 200 ms show delay must be a
   fade, not a later composition.
6. **Correction to the motion research.** The Compose test rule cancels `rememberInfiniteTransition` loops when
   `autoAdvance` is on (verified), so the pulse would not hang tests. Hand-written loops would, so loops are allowed only
   through `rememberInfiniteTransition`.
7. **Screens that leave empty.** Group page `Gone` and Review `NothingWaiting` would slide out as empty frames. Keep the
   last shown state while leaving.
8. **Sign-in gate.** Gated screens push an empty page and open Sign in only after the push ends. Gated targets now
   fade only when signed out.
9. **Account sheet timing.** The Credential Manager sheet is offered at composition, during the modal rise. Move it to
   the first RESUMED.
10. **RTL.** Use `slideIntoContainer(Start/End)`. The review card's fly direction stays physical (right adds), as
    `docs/product.md` says.
11. **Accessibility.** `liveRegion` text must not sit inside `AnimatedContent`, and leaving content is hidden from
    accessibility.
12. **Typing.** No `AnimatedContent` keyed by typed text: it would rebuild the field and close the keyboard.
13. **Dialog exits.** These are driven by state with the last content kept, not by delayed callbacks. Otherwise a
    rename Save refused by validation would hide the dialog, and tests that assert right after a click would break.
14. **Stray press haptic in the swipe card.** A drag that starts on the card's Split button plays a press, then cancels.
    The press haptic is off for buttons inside the draggable card.
15. **Shake on a repeated mistake.** A second refused submit does not change `amountError`, so it needs a counter. That
    is a small ViewModel change, and ViewModel tests that compare the whole state need updating.
16. **Window colours.** The cold-start colour must come from the tokens (`gen_theme.py`), not from hex typed into XML.
17. **Captures.** Captures need reduced motion by default; otherwise the pulse phase and half-finished pops make
    screenshots differ from the design.
18. **Dialog window animation.** The dialog window may add its own animation, because the Compose `DialogWindowTheme`
    sets none and inherits the activity's. Check it on the device.

### 8.2 Conflicts resolved

| Conflict | Resolution |
|---|---|
| Press down: 50 ms LinearOutSlowIn or 40 ms linear | 40 ms linear |
| Minimum press time: 90 or 100 ms | 100 ms |
| Release spring: 0.55/1500 or 0.5/1500 | 0.5/1500 |
| Cancel: spring or tween | `tween(120)`, no bounce |
| Press hold with Remove animations: skip or keep | Keep; it is state, not motion |
| Overlay strength: 8% or 10% | 10% |
| Haptic on the nav pill | None |
| Scale-down on chips while pressed | None |
| Stamp: pop when it first shows or at the commit line | Fade when it first shows, pop at the commit line, with the haptic |
| Success screens: transition, pop and stagger | Transition and pop |
| OptionCard: rise, pop and colour | Pop and colour |
| Reject: on many forms (motion research) or Add expense only (haptics research) | Add expense is must, the rest should |
| Haptics helper location | `ui/theme/Haptics.kt` |
| Bottom navigation hoisted or not | Not hoisted; see section 7 |

### 8.3 Risks to watch

- **Low-end performance:**
  - the first composition of a heavy screen (Activity, Group page) lands on the first transition frame; the fade delay
    hides it, and a Baseline Profile helps
  - list-wide `animateItem` on an Activity filter change
  - full-screen alpha layers
  - AppSwitch's per-frame recomposition (fixed in 2.4)
- **Haptics:** binder cost and wear are kept down by the throttle; cheap motors are spared by the rich-haptics gate.
- **Accessibility:** TalkBack (2.5 and 3.8); motion distances of 30 dp or less and scale 0.9 or more; the pulse is under
  1 Hz, far below flash limits; Remove animations is followed everywhere (3.7).
- **Scope:** about 60 files. File ownership in section 6 keeps the two engineers from editing the same files.

## Sources

- Android: [haptic feedback](https://developer.android.com/develop/ui/views/haptics/haptic-feedback),
  [haptics principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles),
  [HapticFeedbackConstantsCompat](https://developer.android.com/reference/androidx/core/view/HapticFeedbackConstantsCompat),
  [Compose HapticFeedbackType](https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType),
  [AOSP haptics UX](https://source.android.com/docs/core/interaction/haptics/haptics-ux-design)
- Compose:
  [handling interactions (same-frame press, minimum visible time, custom Indication)](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions),
  [MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale),
  [list item animations](https://developer.android.com/develop/ui/compose/lists#item-animations)
- Navigation:
  [predictive back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture),
  [release notes](https://developer.android.com/jetpack/androidx/releases/navigation),
  [DefaultNavTransitions](https://developer.android.com/reference/kotlin/androidx/navigation/compose/DefaultNavTransitions)
- Material:
  [motion tokens, springs, shared axis, fade through](https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md),
  [Compose MotionScheme](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)
- [Josh W. Comeau, 3D button](https://www.joshwcomeau.com/animation/3d-button/) (fast press, springy release)
- Checked in this project's Gradle cache:
  - navigation-compose 2.10.2: the `NavHost` overload with `predictivePopEnter/ExitTransition (scope, Int)`;
    `DefaultNavTransitions` is a 700 ms fade, with predictive pop `scaleOut(0.7)`
  - ui-test 1.12.1: infinite animations are cancelled when `autoAdvance` is on
  - ui 1.12.1: `DialogWindowTheme` has no `windowAnimationStyle`; `MotionDurationScaleImpl` observes the system scale
  - foundation 1.12.1 and core 1.19.1: see the haptics research
