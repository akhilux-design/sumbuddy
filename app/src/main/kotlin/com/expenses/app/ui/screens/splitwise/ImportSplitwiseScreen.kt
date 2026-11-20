package com.expenses.app.ui.screens.splitwise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.expenses.app.R
import com.expenses.app.ui.motion.FadeThroughContent
import com.expenses.app.ui.motion.RevealValue
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.AppInputState
import com.expenses.app.ui.components.Banner
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.InfoRow
import com.expenses.app.ui.components.OptionCard
import com.expenses.app.ui.components.PageFrame
import com.expenses.app.ui.components.ProgressBar
import com.expenses.app.ui.components.SectionLabel
import com.expenses.app.ui.components.SettingRow
import com.expenses.app.ui.components.StickerTile
import com.expenses.app.ui.components.StickerTone
import com.expenses.app.ui.components.SurfaceCard
import com.expenses.app.ui.components.SurfaceCardStyle
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.format.formatCount
import com.expenses.app.ui.format.formatRupees
import com.expenses.app.ui.icons.AppIcon
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space
import com.expenses.splitwise.Refusal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Outline weight of the drop area (Figma: 1.5, inside, dashes of 6 with gaps of 6). Figma counts this stroke in the
 * frame's layout, so the content starts one stroke further in than the padding on every side: the frames of 28 and 30
 * are 113 high, 135 is 95 and 136 is 131, each 3 more than its padding and text.
 */
private val DropStroke = 1.5.dp

/** Length of one dash of the drop area's outline, and of the gap after it. */
private val DropDash = 6.dp

/**
 * Import from Splitwise (design 02-everyday/28 to 30 and 133 to 140; docs/splitwise-import.md 5.7), one layout per
 * [ImportUiState]:
 * - 28, 135, 30 and 136: the headline, the drop area (no file, reading, or why the file cannot come across) and the
 *   three Info Rows, with Choose file (disabled while reading) or Choose another file.
 * - 29, 133 and 134: the file, the other-currency note (134), the Group name field, "Which one is you?" with an
 *   Option Card per person, the Info Rows, then Import (off while nobody is chosen or the name is blank, 133) and
 *   Choose a different file.
 * - 137: the file, the note that it came across before, Open {group} and Choose a different file.
 * - 138 and 139: no top bar; the box tile, "Bringing {group} across", the progress and Open {group}, with the
 *   Offline or Problem banner on top.
 * - 140: no top bar; the alert tile, "{group} did not come across", Choose another file and Close.
 *
 * @param state what to show.
 * @param onEvent called with what the person did.
 * @param modifier outer modifier.
 */
@Composable
fun ImportSplitwiseScreen(state: ImportUiState, onEvent: (ImportEvent) -> Unit, modifier: Modifier = Modifier) {
    // Choosing a file, importing and failed swap with a fade-through; the steps of choosing a file change in place.
    FadeThroughContent(targetState = state, contentKey = { it.mode() }) { state ->
        when (state) {
            is ImportUiState.Importing -> ImportingContent(state, onEvent, modifier)
            is ImportUiState.Failed -> FailedContent(state, onEvent, modifier)
            else -> PageFrame(
                title = stringResource(R.string.import_title),
                onBack = { onEvent(ImportEvent.Back) },
                modifier = modifier,
                footer = { Footer(state, onEvent) },
            ) {
                Headline()
                when (state) {
                    ImportUiState.NoFile -> {
                        DropArea(stringResource(R.string.import_no_file_title), stringResource(R.string.import_no_file_body), problem = false)
                        InfoRows()
                    }
                    is ImportUiState.Reading -> {
                        DropArea(stringResource(R.string.import_reading_title), state.fileName, problem = false)
                        InfoRows()
                    }
                    is ImportUiState.CannotImport -> {
                        val (title, body) = refusalTexts(state.reason)
                        DropArea(stringResource(title), stringResource(body, state.fileName), problem = true)
                        InfoRows()
                    }
                    is ImportUiState.Chosen -> ChosenContent(state, onEvent)
                    is ImportUiState.AlreadyHere -> {
                        FileRow(state.fileName, state.expenseCount, state.peopleCount)
                        Note(stringResource(R.string.import_already_note, state.groupName, shortDate(state.on)))
                        InfoRows()
                    }
                    is ImportUiState.Importing, is ImportUiState.Failed -> Unit
                }
            }
        }
    }
}

/** The title and body string of a [Refusal], as the table of docs/splitwise-import.md 5.7 gives them. */
internal fun refusalTexts(reason: Refusal): Pair<Int, Int> = when (reason) {
    Refusal.NOT_SPLITWISE -> R.string.import_wrong_title to R.string.import_wrong_body
    Refusal.DOES_NOT_ADD_UP -> R.string.import_changed_title to R.string.import_changed_body
    Refusal.TOO_LARGE_FILE -> R.string.import_too_big_title to R.string.import_too_large_file_body
    Refusal.TOO_MANY_PEOPLE -> R.string.import_too_big_title to R.string.import_too_many_people_body
    Refusal.TOO_MANY_ROWS -> R.string.import_too_big_title to R.string.import_too_many_rows_body
    Refusal.TOO_LARGE_AMOUNT -> R.string.import_amount_title to R.string.import_amount_body
    Refusal.NOTHING_IN_RUPEES -> R.string.import_no_rupees_title to R.string.import_no_rupees_body
    Refusal.ONE_PERSON -> R.string.import_one_person_title to R.string.import_one_person_body
}

/** Display/L "Bring a group from Splitwise" and the Body/M intro. */
@Composable
private fun Headline() {
    AppText(
        text = stringResource(R.string.import_headline),
        style = AppTheme.type.displayL,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    AppText(
        text = stringResource(R.string.import_intro),
        style = AppTheme.type.bodyM,
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.textSecondary,
    )
}

/**
 * The design's Drop area: radius 16 on `color/bg/surface`, a dashed 1.5dp outline (`color/edge/neutral`, or
 * `color/status/negative` when the file cannot come across), then, inside the outline ([DropStroke]), 24dp above and
 * below and 16dp at the sides, with a centred Title and Caption 4dp apart. The caption is red when [problem]; the
 * title says what is wrong, so it never shows by colour alone.
 */
@Composable
private fun DropArea(title: String, body: String, problem: Boolean) {
    val colors = AppTheme.colors
    val outline = if (problem) colors.statusNegative else colors.edgeNeutral
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // One node for the whole area, outline and padding included.
            .semantics(mergeDescendants = true) {}
            .background(colors.bgSurface, RoundedCornerShape(Radius.lg))
            .dashedOutline(outline)
            .padding(DropStroke)
            .padding(horizontal = Space.s16, vertical = Space.s24),
        verticalArrangement = Arrangement.spacedBy(Space.s4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText(text = title, style = AppTheme.type.title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        AppText(
            text = body,
            style = AppTheme.type.caption,
            modifier = Modifier.fillMaxWidth(),
            color = if (problem) colors.statusNegative else colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** A dashed rounded outline drawn inside the bounds, as Figma draws a centred stroke on a clipped frame. */
private fun Modifier.dashedOutline(color: Color): Modifier = drawBehind {
    val stroke = DropStroke.toPx()
    val dash = DropDash.toPx()
    val inset = stroke / 2
    val radius = Radius.lg.toPx() - inset
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}

/** "What comes across", "One file, one group" and "Names only", with their 3D icons. */
@Composable
private fun InfoRows() {
    InfoRow(stringResource(R.string.import_what_title), stringResource(R.string.import_what_body), icon = AppIcon.Box)
    InfoRow(stringResource(R.string.import_one_file_title), stringResource(R.string.import_one_file_body), icon = AppIcon.Folder)
    InfoRow(stringResource(R.string.import_names_title), stringResource(R.string.import_names_body), icon = AppIcon.Link)
}

/** The design's File frame: 8dp above and below a Setting Row with the 3D file, the name and "46 expenses · 3 people". */
@Composable
private fun FileRow(fileName: String, expenseCount: Int, peopleCount: Int) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = Space.s8)) {
        SettingRow(
            title = fileName,
            subtitle = stringResource(
                R.string.import_file_counts,
                pluralStringResource(R.plurals.import_expenses, expenseCount, formatCount(expenseCount)),
                pluralStringResource(R.plurals.import_people, peopleCount, formatCount(peopleCount)),
            ),
            icon = AppIcon.File,
        )
    }
}

/** The design's Note: a short Body/M paragraph on `color/bg/surface`, 12dp above and below, 16dp at the sides. */
@Composable
private fun Note(text: String) {
    SurfaceCard(style = SurfaceCardStyle.Note) {
        AppText(text = text, style = AppTheme.type.bodyM, modifier = Modifier.fillMaxWidth(), color = AppTheme.colors.textPrimary)
    }
}

/** 29, 133 and 134. */
@Composable
private fun ChosenContent(state: ImportUiState.Chosen, onEvent: (ImportEvent) -> Unit) {
    FileRow(state.fileName, state.expenseCount, state.people.size)
    if (state.leftOut > 0) {
        Note(pluralStringResource(R.plurals.import_left_out, state.leftOut, formatCount(state.leftOut)))
    }
    val blank = state.cleanName.isEmpty()
    AppInput(
        value = state.groupName,
        onValueChange = { onEvent(ImportEvent.NameChanged(it)) },
        label = stringResource(R.string.import_group_name),
        modifier = Modifier.fillMaxWidth(),
        helper = stringResource(R.string.import_group_name_error),
        state = if (blank) AppInputState.Error else AppInputState.Default,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
    )
    SectionLabel(text = stringResource(R.string.import_which_one))
    // The design's People frame: Option Cards 8dp apart, one radio group.
    Column(modifier = Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        state.people.forEachIndexed { index, person ->
            OptionCard(
                title = person.name,
                subtitle = balanceText(person.balanceMinor),
                icon = AppIcon.User,
                selected = state.selected == index,
                onClick = { onEvent(ImportEvent.PersonSelected(index)) },
            )
        }
    }
    InfoRows()
}

/** "Is owed ₹2,350", "Owes ₹1,150" or "All settled". */
@Composable
private fun balanceText(balanceMinor: Long): String = when {
    balanceMinor > 0 -> stringResource(R.string.import_person_owed, formatRupees(balanceMinor))
    balanceMinor < 0 -> stringResource(R.string.import_person_owes, formatRupees(abs(balanceMinor)))
    else -> stringResource(R.string.import_person_settled)
}

/** The footer of the states with a top bar. */
@Composable
private fun ColumnScope.Footer(state: ImportUiState, onEvent: (ImportEvent) -> Unit) {
    val choose: @Composable (Int, ButtonStyle, ButtonSize) -> Unit = { label, style, size ->
        AppButton(
            label = stringResource(label),
            onClick = { onEvent(ImportEvent.ChooseFile) },
            modifier = Modifier.fillMaxWidth(),
            style = style,
            size = size,
            enabled = state !is ImportUiState.Reading && !(state is ImportUiState.Chosen && state.importing),
        )
    }
    when (state) {
        ImportUiState.NoFile, is ImportUiState.Reading -> choose(R.string.import_choose_file, ButtonStyle.Primary, ButtonSize.Large)
        is ImportUiState.CannotImport -> choose(R.string.import_choose_another, ButtonStyle.Primary, ButtonSize.Large)
        is ImportUiState.Chosen -> {
            val name = state.cleanName
            AppButton(
                label = if (name.isEmpty()) stringResource(R.string.import_action_no_name) else stringResource(R.string.import_action, name),
                onClick = { onEvent(ImportEvent.Import) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canImport,
            )
            choose(R.string.import_choose_different, ButtonStyle.Ghost, ButtonSize.Medium)
        }
        is ImportUiState.AlreadyHere -> {
            AppButton(
                label = stringResource(R.string.import_open_group, state.groupName),
                onClick = { onEvent(ImportEvent.OpenGroup) },
                modifier = Modifier.fillMaxWidth(),
            )
            choose(R.string.import_choose_different, ButtonStyle.Ghost, ButtonSize.Medium)
        }
        is ImportUiState.Importing, is ImportUiState.Failed -> Unit
    }
}

/** 138 and 139: no top bar; Back is the caller's (it opens the group, as Open {group} does). */
@Composable
private fun ImportingContent(state: ImportUiState.Importing, onEvent: (ImportEvent) -> Unit, modifier: Modifier) {
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.import_open_group, state.groupName),
                onClick = { onEvent(ImportEvent.OpenGroup) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        // The design's body starts 40dp below the status bar: 8 from the page, 20 here and the 12dp gap.
        Spacer(Modifier.height(Space.s20))
        ImportBannerFor(state.banner)
        StickerTile(icon = AppIcon.Box, tone = StickerTone.Brand)
        AppText(
            text = stringResource(R.string.import_sending_title, state.groupName),
            style = AppTheme.type.displayL,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        AppText(
            text = stringResource(R.string.import_sending_body),
            style = AppTheme.type.bodyM,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            ProgressBar(done = state.sent, total = state.total)
            AppText(
                text = stringResource(R.string.import_sending_progress, formatCount(state.sent), formatCount(state.total)),
                style = AppTheme.type.caption,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

/** The Offline (Icon/wifi-off) or Problem (Icon/alert) banner of 139, 141 and their alternatives; nothing for None. */
@Composable
internal fun ImportBannerFor(banner: ImportBanner) {
    // Opens and closes like Reveal when it arrives or goes after the screen is shown.
    RevealValue(banner.takeIf { it != ImportBanner.None }) { shown -> ImportBannerView(shown) }
}

/** One banner of [ImportBannerFor]. */
@Composable
private fun ImportBannerView(banner: ImportBanner) {
    when (banner) {
        ImportBanner.None -> Unit
        ImportBanner.Offline -> Banner(
            title = stringResource(R.string.import_offline_title),
            subtitle = stringResource(R.string.import_offline_body),
            icon = AppIcon.WifiOff,
        )
        ImportBanner.Problem -> Banner(
            title = stringResource(R.string.import_problem_title),
            subtitle = stringResource(R.string.import_problem_body),
            icon = AppIcon.Alert,
        )
    }
}

/** 140. */
@Composable
private fun FailedContent(state: ImportUiState.Failed, onEvent: (ImportEvent) -> Unit, modifier: Modifier) {
    PageFrame(
        title = null,
        onBack = null,
        modifier = modifier,
        footer = {
            AppButton(
                label = stringResource(R.string.import_choose_another),
                onClick = { onEvent(ImportEvent.ChooseFile) },
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                label = stringResource(R.string.import_close),
                onClick = { onEvent(ImportEvent.Close) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Ghost,
                size = ButtonSize.Medium,
            )
        },
    ) {
        Spacer(Modifier.height(Space.s20))
        StickerTile(icon = AppIcon.Alert, tone = StickerTone.Yellow)
        AppText(
            text = stringResource(R.string.import_failed_title, state.groupName),
            style = AppTheme.type.displayL,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        AppText(
            text = stringResource(R.string.import_failed_body),
            style = AppTheme.type.bodyL,
            modifier = Modifier.fillMaxWidth(),
            color = AppTheme.colors.textSecondary,
        )
    }
}

/** A day as 137 writes it, "18 Nov", with month names in the phone's language. */
@Composable
private fun shortDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    return date.format(formatter)
}

/** Which part of Import from Splitwise [this] shows: choosing a file (all its steps), importing, or failed. */
private fun ImportUiState.mode(): Int = when (this) {
    is ImportUiState.Importing -> 1
    is ImportUiState.Failed -> 2
    else -> 0
}
