package com.expenses.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import com.expenses.app.ui.components.core.EdgeHeight

// Layout helpers shared by the components. They change how big an element says it is, never what it draws.

/**
 * The opposite of `padding`: lets an element reach past its place in the layout, so its touch area can be 48dp
 * while the layout keeps the sizes and gaps of the design. The parent measures and places the element as if the
 * extra area were not there; the extra area lies over the space next to it. Follow it with the click modifier and
 * then a `padding` of the same amounts, so the content stays exactly where the design puts it. Because the touch
 * area really is the element's own, it keeps working inside a parent that is tappable too.
 *
 * @param horizontal how far the element reaches past its place on the left and on the right.
 * @param vertical how far the element reaches past its place above and below.
 */
internal fun Modifier.touchOverhang(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val reachX = horizontal.roundToPx()
    val reachY = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * reachX, vertical = 2 * reachY))
    val width = (placeable.width - 2 * reachX).coerceAtLeast(0)
    val height = (placeable.height - 2 * reachY).coerceAtLeast(0)
    layout(width, height) {
        placeable.place(-reachX, -reachY)
    }
}

/**
 * Lays a raised element (an [AppButton], a card) out as high as its face alone: the [EdgeHeight] it keeps under the
 * face for its edge is left out of its place in the layout, and the edge is drawn over the space below. This is a
 * Figma instance whose height is fixed at the face's height (a Primary Large Button at 52 where the component is 57),
 * as some screen frames draw the buttons of their footer. Use it only where the frame does, and only where the space
 * below is at least [EdgeHeight] and not clipped. What the element draws and where it reacts to touch do not change.
 */
internal fun Modifier.faceHeightOnly(): Modifier = layout { measurable, constraints ->
    val edge = EdgeHeight.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = edge))
    layout(placeable.width, (placeable.height - edge).coerceAtLeast(0)) {
        placeable.place(0, 0)
    }
}
