package com.expenses.app.preview.screens

import com.expenses.app.preview.PreviewEntry
import com.expenses.app.ui.screens.splitwise.GroupImportedScreen
import com.expenses.app.ui.screens.splitwise.GroupImportedUiState
import com.expenses.app.ui.screens.splitwise.ImportSplitwiseSamples
import com.expenses.app.ui.screens.splitwise.ImportSplitwiseScreen
import com.expenses.app.ui.screens.splitwise.ImportUiState
import com.expenses.splitwise.Refusal

/**
 * Import from Splitwise and Group imported, one entry per design state in design/spec/screens/02-everyday (for
 * design/tools/capture.sh):
 * - "screen-splitwise-no-file" (28-import-from-splitwise)
 * - "screen-splitwise-reading" (135-import-from-splitwise-reading)
 * - "screen-splitwise-chosen" (29-import-from-splitwise-file-chosen)
 * - "screen-splitwise-which-one" (133-import-from-splitwise-which-one-is-you)
 * - "screen-splitwise-other-currencies" (134-import-from-splitwise-other-currencies)
 * - "screen-splitwise-wrong-file" (30-import-from-splitwise-wrong-file)
 * - "screen-splitwise-cannot-import" (136-import-from-splitwise-cannot-import)
 * - "screen-splitwise-cannot-import-too-large-file", "-too-many-people", "-too-many-rows", "-too-large-amount",
 *   "-nothing-in-rupees" and "-one-person" (136's alternatives, drawn once in design/spec/README.md)
 * - "screen-splitwise-already-here" (137-import-from-splitwise-already-here)
 * - "screen-splitwise-importing" (138-importing)
 * - "screen-splitwise-importing-offline" (139-importing-offline)
 * - "screen-splitwise-importing-problem" (139's alternative, the Problem banner)
 * - "screen-splitwise-failed" (140-import-failed)
 * - "screen-group-imported" (31-group-imported)
 * - "screen-group-imported-offline" (141-group-imported-offline)
 * - "screen-group-imported-problem" (141's alternative), "screen-group-imported-one-friend" and
 *   "screen-group-imported-many" (31's README notes for one friend and four or more)
 * - Not drawn: "screen-splitwise-name-blank" (the file name gave no group name).
 */
val splitwiseScreenPreviews: List<PreviewEntry> = listOf(
    importPreview("screen-splitwise-no-file", ImportSplitwiseSamples.noFile),
    importPreview("screen-splitwise-reading", ImportSplitwiseSamples.reading),
    importPreview("screen-splitwise-chosen", ImportSplitwiseSamples.chosen),
    importPreview("screen-splitwise-which-one", ImportSplitwiseSamples.whichOne),
    importPreview("screen-splitwise-other-currencies", ImportSplitwiseSamples.otherCurrencies),
    importPreview("screen-splitwise-name-blank", ImportSplitwiseSamples.nameBlank),
    importPreview("screen-splitwise-wrong-file", ImportSplitwiseSamples.wrongFile),
    importPreview("screen-splitwise-cannot-import", ImportSplitwiseSamples.cannotImport()),
    importPreview("screen-splitwise-cannot-import-too-large-file", ImportSplitwiseSamples.cannotImport(Refusal.TOO_LARGE_FILE)),
    importPreview("screen-splitwise-cannot-import-too-many-people", ImportSplitwiseSamples.cannotImport(Refusal.TOO_MANY_PEOPLE)),
    importPreview("screen-splitwise-cannot-import-too-many-rows", ImportSplitwiseSamples.cannotImport(Refusal.TOO_MANY_ROWS)),
    importPreview("screen-splitwise-cannot-import-too-large-amount", ImportSplitwiseSamples.cannotImport(Refusal.TOO_LARGE_AMOUNT)),
    importPreview("screen-splitwise-cannot-import-nothing-in-rupees", ImportSplitwiseSamples.cannotImport(Refusal.NOTHING_IN_RUPEES)),
    importPreview("screen-splitwise-cannot-import-one-person", ImportSplitwiseSamples.cannotImport(Refusal.ONE_PERSON)),
    importPreview("screen-splitwise-already-here", ImportSplitwiseSamples.alreadyHere),
    importPreview("screen-splitwise-importing", ImportSplitwiseSamples.importing),
    importPreview("screen-splitwise-importing-offline", ImportSplitwiseSamples.importingOffline),
    importPreview("screen-splitwise-importing-problem", ImportSplitwiseSamples.importingProblem),
    importPreview("screen-splitwise-failed", ImportSplitwiseSamples.failed),
    importedPreview("screen-group-imported", ImportSplitwiseSamples.imported),
    importedPreview("screen-group-imported-offline", ImportSplitwiseSamples.importedOffline),
    importedPreview("screen-group-imported-problem", ImportSplitwiseSamples.importedProblem),
    importedPreview("screen-group-imported-one-friend", ImportSplitwiseSamples.importedOneFriend),
    importedPreview("screen-group-imported-many", ImportSplitwiseSamples.importedMany),
)

private fun importPreview(name: String, state: ImportUiState): PreviewEntry =
    PreviewEntry(name = name, fullScreen = true) { ImportSplitwiseScreen(state = state, onEvent = {}) }

private fun importedPreview(name: String, state: GroupImportedUiState): PreviewEntry =
    PreviewEntry(name = name, fullScreen = true) { GroupImportedScreen(state = state, onEvent = {}) }
