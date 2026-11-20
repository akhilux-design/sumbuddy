# Motion

Exported from the Figma Design System page, `Section/Motion` (node `382:220`, between Components and Explorations).
It records the motion and haptics of `docs/motion.md`. The owner rules at the top of that file win wherever its body
disagrees, so this file differs from the body in places (listed at the end).

Everything that moves, scales or resizes is a spring (a damping ratio and a stiffness), so it feels physical and an
interrupted motion carries on from where it is. Fades and colour changes are short tweens. Durations below are targets
used to pick each spring. The app uses the same token names.

## Springs

Compose: `spring(dampingRatio, stiffness)`. Figma prototype equivalent: mass 1, damping = 2 x ratio x sqrt(stiffness).

| Token | Damping ratio | Stiffness | Figma spring (mass, stiffness, damping) | Feel | Used for |
|---|---|---|---|---|---|
| `motion/spring/press` | 1.0 | 3800 | 1, 3800, 123.3 | Lands in about 60 ms, no bounce | The 3D face going down onto its edge |
| `motion/spring/press-release` | 0.5 | 1500 | 1, 1500, 38.7 | About 200 ms, about 0.65 dp overshoot | The 3D face coming back up after the press hold |
| `motion/spring/fast` | 0.9 | 1400 | 1, 1400, 67.3 | About 120 ms, no visible bounce | Chip, option card and segment faces; the nav label growing and shrinking |
| `motion/spring/pill` | 0.8 | 1400 | 1, 1400, 59.9 | About 140 ms, slight overshoot (about 1.5%) | Bottom-bar pill x and width, Me/Family pill, switch knob |
| `motion/spring/default` | 0.9 | 700 | 1, 700, 47.6 | About 170 ms | List placement, dialog card, review card fling, return and stack, sheet settle after a drag, Reveal |
| `motion/spring/screen` | 0.9 | 300 | 1, 300, 31.2 | About 260 ms | Screen push and pop, modal rise, bottom sheet in and out, predictive back settle, tab content and result arrival |
| `motion/spring/pop` | 0.5 | 600 | 1, 600, 24.5 | About 320 ms, 16% overshoot | Success sticker, radio dot, review stamp at the commit line |
| `motion/spring/shake` | 0.2 | 1200 | 1, 1200, 13.9 | Swings about 8, 4, 2 dp, rests in about 450 ms | Error shake, started by a sideways kick of about 365 dp/s |

Every spring starts from the current value and velocity (`Animatable.animateTo` keeps both), so an interruption
continues smoothly. Gestures pass their release velocity as `initialVelocity`.

## Duration targets

Rows marked "spring target" are not durations to code: they are what the named spring should roughly take. Rows marked
"tween" are real durations.

| Token | Target | How it is used |
|---|---|---|
| `motion/duration/press` | 60 ms | Spring target for spring/press: the 3D face lands on its edge. The flat pressed overlay fades in over 50 ms (tween). |
| `motion/duration/short` | 150 ms | Tween for fades, colour, border and scrim out. Spring target for small moves: spring/fast, spring/pill. |
| `motion/duration/medium` | 250 ms | Spring target for the dialog card (spring/default) and for Back (spring/screen). Sheet out, tab content. |
| `motion/duration/long` | 300 ms | Spring target for spring/screen: push, modal rise, sheet in, result arrival. |
| `motion/hold/press` | 100 ms | Not motion: a press stays visible at least this long, counted on the frame clock. Kept when Remove animations is on. |
| `motion/delay/placeholder` | 200 ms | Tween delay: loading placeholders fade in only after this. |
| Fade-through | out 90, in 160 ms | Tween for content that swaps in place. The new content starts after 90 ms. |
| Placeholder pulse | 900 ms each way | The only loop: alpha 1 to 0.55 and back, a tween. Static when Remove animations is on. |
| Cap | 350 ms | No tween is longer. Springs have no fixed length; they are tuned to land near their target. |

## Distances and scales

| Token | Value | Used for |
|---|---|---|
| `motion/distance/axis` | 30 dp | Push and pop slide; the screen under a back swipe |
| `motion/distance/press` | 4 dp | 3D face drop; leaves a 1 dp lip of edge |
| `motion/distance/rise` | 24 dp | Dialog card rise |
| `motion/distance/shake` | 8 dp | First swing of the error shake |
| `motion/fraction/modal` | 1/8 of the height | Modal screen rise |
| `motion/scale/enter` | 0.96 | Result screens, arrival at a tab, tab content |
| `motion/scale/dialog` | 0.94 | Dialog card, grown from its bottom centre |
| `motion/scale/predictive` | 0.90 | Leaving screen during a back swipe |
| `motion/scale/pop` | 0.5 | Success sticker start; peaks near 1.08, rests at 1 |
| Review card | 1.5 widths, 12° | Fly-out distance and tilt. The next card grows from 24 dp narrower and 12 dp lower. |
| Commit line | Decide fraction of the stack width | Arms the stamp pop and the threshold tick; disarms 8 dp back under it |
| Sheet dismiss | 30% or 1000 dp/s | A dragged sheet closes past 30% of its height or above this speed |
| `state/pressed` | `color/text/primary` at 10% | Flat pressed overlay on rows, cards, chips and icons |
| `state/pressed-ghost` | `color/brand/soft` | Ghost button pressed pill |

## Haptics

Constants are `HapticFeedbackConstantsCompat` values, played with `ViewCompat.performHapticFeedback` on the activity
root view, so they follow the system Touch feedback setting.

| Token | Android constant | Older phones | When to play |
|---|---|---|---|
| `haptic/press` | `VIRTUAL_KEY` | None needed | A 3D face goes down, on every 3D button (Done, Cancel and Not now included) except the bottom-bar pill and buttons inside the swipe card. Only on phones with rich haptics. |
| `haptic/selection` | `SEGMENT_TICK` | `CONTEXT_CLICK` below API 34 | A tab change in the bottom bar (a light tick). A selection changes in a chip, Me/Family, an option card or a category. |
| `haptic/toggle-on`, `haptic/toggle-off` | `TOGGLE_ON`, `TOGGLE_OFF` | `CONTEXT_CLICK`, `CLOCK_TICK` below API 34 | A switch flips, on its own or from its setting row. |
| `haptic/threshold-in`, `haptic/threshold-out` | `GESTURE_THRESHOLD_ACTIVATE`, `GESTURE_THRESHOLD_DEACTIVATE` | `CONTEXT_CLICK`, `CLOCK_TICK` below API 34 | The review card crosses the commit line, or crosses back. A dragged sheet crosses its dismiss line. |
| `haptic/confirm` | `CONFIRM` | `VIRTUAL_KEY` below API 30 | Something the person started is done: success screens, group created or joined, reminder sent, a dialog action done. |
| `haptic/reject` | `REJECT` | `LONG_PRESS` below API 30 | Something the person tried was refused: error shake, server refused, offline. |

## Rules

Physics
- Every move, scale and resize is a spring. Fades and colour changes are short tweens.
- A spring starts from the current value and speed, so an interrupted motion carries on from where it is. It never
  jumps or restarts.
- Gestures hand over their speed: the review card, a dragged sheet and predictive back continue at the finger's
  release velocity.
- A tap's action is never delayed by its animation.

Remove animations
- When Remove animations is on (animator duration scale 0), screens, dialogs and sheets change with no transition.
- No placeholder pulse, no fling or tilt on the review card and no shake. The next review card replaces the last.
- What stays: the 100 ms press hold, gestures that follow the finger, error messages and every haptic.

Haptic rules
- Haptics follow the system Touch feedback setting, never Remove animations.
- At most one haptic per action. A tick is dropped if another haptic played under 80 ms earlier; confirm and reject
  never are. Confirm comes at least 250 ms after the last press.
- None for Back, scrolling, typing, opening a dialog or sheet, Cancel, Not now, Done, Undo, disabled taps or anything
  the person did not start. The bottom-bar tab change is the one navigation that ticks.
- The press tick is the exception: it plays on every 3D button as its face goes down, Done, Cancel and Not now
  included. A Ghost button has no face and stays silent.

## Patterns

One card each in Figma (`Pattern/<name>`), with a static diagram: a dashed outline is where something starts, a solid
shape is where it settles, and an arrow is the move. The diagrams use Button, Bottom Nav, Chip, Switch, Segmented
Toggle, Expense Row, Option Card, Input and Icon/check-circle instances. There are 18 cards.

- **Screen push and pop** (`384:225`). The new screen slides in 30 dp from the right as it fades in; the old one
  slides 30 dp left as it fades out. Back is the mirror image, and right-to-left languages flip it.
  Spring: spring/screen. Fade: 150 ms tween. Haptic: none.
- **Modal rise** (`384:240`). Add expense, Sign in, Choose category, New group, Add friend and Import from Splitwise
  rise 1/8 of the height as they fade in. The screen under them stays still. Closing drops them back the same way.
  Spring: spring/screen. Fade: 150 ms tween. Haptic: none.
- **Predictive back** (`384:253`). During a back swipe the leaving screen follows the finger: it shrinks to 90% and
  moves toward the swiped edge while the screen under it slides in. On release it carries the finger's speed and
  springs away, or back if the swipe is cancelled. Spring: spring/screen from the release velocity. Haptic: none.
- **Tab change** (`384:269`). One green pill slides from the old tab to the new one; its x and width spring with a
  slight overshoot. The new label fades in, the tab content fades in from 96%, and a light selection tick plays.
  Spring: spring/pill (pill), spring/screen (content). Haptic: haptic/selection.
- **Bottom sheet** (`384:330`). The sheet rises from below as the scrim fades in. Dragged down and let go, it keeps
  the finger's speed: past 30% of its height or faster than 1000 dp/s it closes, otherwise it springs back up.
  Spring: spring/screen (open, close), spring/default (settle after a drag). Haptic: threshold-in and threshold-out at
  the dismiss line.
- **Dialog** (`384:344`). The scrim fades in while the card rises 24 dp and grows from 94% out of its bottom centre.
  It leaves faster than it came: it fades and shrinks to 96%. Taps are ignored while it leaves.
  Spring: spring/default. Fade: 150 ms tween. Haptic: none on open; confirm or reject on the result.
- **3D press and release** (`384:356`). On touch the face springs down 4 dp onto its edge, leaving a 1 dp lip, with
  no bounce. On release it springs back up with a slight overshoot. A press shows for at least 100 ms, so even a quick
  tap shows the full press. The tap's action is never delayed. Spring: spring/press down, spring/press-release up.
  Hold: 100 ms. Haptic: haptic/press. In the diagram the pressed Button is the Button's `State=Pressed` variant: the
  Face 4 lower with a 1 dp edge (see `components/Button.md`).
- **Chip and toggle** (`384:373`). A chip changes fill, label and outline over 150 ms. A switch knob and the
  Me/Family pill slide with a slight overshoot. Each plays one tick when the value changes, never on a repeat tap.
  Spring: spring/pill (knob, pill). Fade: 150 ms tween (colours). Haptic: haptic/selection, haptic/toggle-on or
  toggle-off.
- **List insert and remove** (`384:401`). A new row fades in where it belongs and the rows below slide down to make
  room. A removed row fades out and the rest slide up. Rows already there when the screen opens do not animate.
  Spring: spring/default (placement). Fade: 150 ms tween. Haptic: none.
- **Review swipe fling** (`384:480`). The card follows the finger. Let go past the commit line and it flies out at
  the finger's speed, tilting up to 12°; short of the line it springs back from that speed. Crossing the line pops the
  stamp and ticks. The next card grows from 24 dp narrower. Spring: spring/default with the release velocity,
  spring/pop (stamp). Haptic: threshold-in, threshold-out.
- **Success** (`384:492`). About 100 ms after a result screen arrives, the sticker pops from 50% with a slight
  overshoot and fades in, and a confirm haptic plays once. It does not replay when the screen is rebuilt.
  Spring: spring/pop. Fade: 150 ms tween. Haptic: haptic/confirm.
- **Error shake** (`384:512`). A refused submit kicks the field sideways: a loose spring swings about 8, 4 and 2 dp
  and settles while the error message shows and a reject haptic plays. With Remove animations on there is no shake;
  the message and the haptic stay. Spring: spring/shake. Haptic: haptic/reject.

- **Ghost pressed** (`422:467`). A Ghost button has no face or edge, so while it is pressed a soft green pill
  (`color/brand/soft`, the face's shape, radius 16) appears behind its label. Nothing moves. A press shows for at least
  100 ms. Colour: `color/brand/soft` (`state/pressed-ghost`). Fade: 50 ms in, 150 ms out. Haptic: none. The diagram uses
  Button Ghost `State=Default` and `State=Pressed`.
- **Flat pressed overlay** (`422:491`). Rows, cards, tiles, chips and icon buttons that do not rise get an overlay while
  pressed: `color/text/primary` at 10%, inside their own shape. No movement, scale or ripple. It stays until 100 ms
  after the touch; a cancelled press fades out over 100 ms. Colour: `color/text/primary` at 10% (`state/pressed`).
  Fade: 50 ms in, 150 ms out. Haptic: none. The diagram uses Expense Row `State=Default` and `State=Pressed`.
- **Option radio pop** (`422:554`). Choosing an option fills its card and border over 150 ms while the radio dot pops
  from nothing to full size with a slight overshoot. The card does not rise as well: one motion per change. A tick
  plays when the choice changes, never on a repeat tap. Spring: spring/pop (dot). Fade: 150 ms tween (face, border,
  ring). Haptic: haptic/selection.
- **Result arrival** (`422:617`). A result screen (Expense added, Split added, Balance cleared, Group imported) fades in
  and grows from 96% while the form under it fades out. Arriving at a tab from outside works the same way. The sticker
  pop follows (Success). Spring: spring/screen (scale). Fade: 250 ms in, 150 ms out. Haptic: confirm, with the sticker.
- **Reveal** (`422:638`). A part that appears after the screen is shown (a banner, a helper line, a form section, a new
  row) opens down from its top edge as it fades in, and what is below slides down to make room. Hiding it is the
  reverse, a little faster. Nothing reveals when a screen first opens. Spring: spring/default (open). Fade: 150 ms in,
  100 ms out. Haptic: none.
- **Placeholder pulse** (`422:667`). Loading placeholders show only if loading takes longer than 200 ms, then fade in
  over 150 ms. All bars on a screen pulse together, from full to 55% and back, 900 ms each way. It is the only loop in
  the app. With Remove animations on they stay still. Tween: 900 ms each way, repeating. Delay: 200 ms. Haptic: none.

## Where this differs from the body of docs/motion.md

The owner rules come first, so:

- Push, pop, modal rise, sheet, dialog, tab content, result arrival, list placement, the review fling and the press
  use springs, not the tweens and easings of sections 1.1, 1.2 and 2. The easing tokens are not used for movement.
- A bottom-bar tab change plays `haptic/selection` (the body says never a haptic on a tab switch).
- `haptic/press` plays on every 3D button, Done, Cancel and Not now included (lead decision 2026-11-20; the body
  lists Cancel, Not now and Done under never a haptic).
- The bottom-bar pill, the Me/Family pill and the switch knob use `motion/spring/pill` (damping 0.8, slight
  overshoot) in place of `motion/spring/fast`.
- New tokens: `motion/spring/press` (face down, replacing the 40 ms linear tween), `motion/spring/pill`,
  `motion/spring/screen`, `motion/spring/shake` (replacing the shake keyframes), `motion/distance/shake` and
  `motion/scale/pop`.
- The review fling past the commit line is spring/default with the release velocity, not a clamped linear tween.
- `motion/duration/press` is 60 ms: the time spring/press takes to land (within about 0.5 dp of the edge). The plan's
  40 ms linear tween is not used.
- `state/pressed` and `state/pressed-ghost` are not Figma variables. The Pressed variants use what the plan defines them
  as: `color/text/primary` at 10% layer opacity, and `color/brand/soft`.
