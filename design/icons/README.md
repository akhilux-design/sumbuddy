# Icons

49 icons from the Figma design system (Design System page, block "Icons"), each in two styles:

| Style | SVG source | Android drawable | Size | Colour |
|---|---|---|---|---|
| Line | `svg/<name>_line.svg` | `app/src/main/res/drawable/ic_<name>_line.xml` | 24dp | White, tinted in code |
| 3D | `svg/<name>_3d.svg` | `app/src/main/res/drawable/ic_<name>_3d.xml` | 32dp | Fixed gradients, never tinted |

`<name>` is the Figma name after `Icon/`, with `-` replaced by `_` (`Icon/check-circle` is `check_circle`).
In Kotlin each icon is an entry of `AppIcon` (`AppIcon.CheckCircle`), generated from the drawables.

Rows, tiles and empty states use the 3D style (`ArtIcon`). The bottom bar and top bars use the line style.
The line icons, the bottom bar icons and the back arrow are based on Feather (https://feathericons.com, MIT
licence, Copyright (c) 2013-2023 Cole Bemis); the notice is in `THIRD_PARTY_NOTICES.md`.
The bottom bar and back arrow have their own drawables (`ic_nav_home`, `ic_nav_groups`, `ic_nav_activity`,
`ic_nav_you`, `ic_back`), taken from the Bottom Nav and Top Bar components.

## Names

alert, bag, ban, bank, bell, bell_off, box, calculator, calendar, car, card, cart, cash, chart, check,
check_circle, clock, copy, edit, eye, eye_off, file, film, folder, food, heart, help, home, link, lock,
logout, mail, message, phone, plane, plus, pointer, receipt, repeat, search, sparkle, split, sprout,
target, trash, user, user_plus, users, wifi_off

## Updating icons

1. Export the changed icons from Figma as SVG into `svg/` with the names above.
2. Convert them (uses Google's own SVG converter, the one behind Android Studio's Vector Asset import):

```bash
cd design/tools/svg2vd && JAVA_HOME=/opt/homebrew/opt/openjdk@21 gradle run
```

3. Regenerate the Kotlin list:

```bash
python3 design/tools/gen_icons.py
```

The converter fails loudly if an icon would not survive the conversion. It keeps elliptical radial
gradients (which Android draws only as circles) by drawing the circle inside a squashed group.
