package com.expenses.app.ui.screens.splitwise

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.data.GroupsRepository
import com.expenses.app.groups.data.ImportProgress
import com.expenses.app.groups.data.ImportState
import com.expenses.app.ui.screens.onboarding.Effects
import com.expenses.splitwise.GroupNameFromFile
import com.expenses.splitwise.Refusal
import com.expenses.splitwise.SplitwiseCsv
import com.expenses.splitwise.SplitwiseFile
import com.expenses.splitwise.SplitwisePeople
import com.expenses.splitwise.SplitwiseResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Import from Splitwise (docs/splitwise-import.md 5.7): a picked file is read on the phone, checked
 * ([SplitwiseCsv.parse]), compared with earlier imports by its hash, and shown with "Which one is you?"; Import then
 * writes the group to this phone at once ([GroupsRepository.importSplitwise]) and follows its parts until the server
 * has them all, offline included. The file stays in memory only and nothing of it is logged.
 *
 * @param readFile reads a picked document ([readPicked]). It may take seconds (a file kept in Drive is fetched first):
 * "Reading the file" (135) shows from the moment the picker returns, with Choose file off.
 * @param profileName the signed-in person's name, for the preselected card ([SplitwisePeople.preselect]).
 * @param online whether the phone is online now (139's banner).
 * @param saved keeps only the id of a started import, so it is followed again after the process was stopped.
 * @param io where the group is written.
 * @param default where the file is parsed and hashed.
 * @param fileName the picked document's display name alone ([pickedName]), asked before the file is read so 135 can
 * name it meanwhile; null when the provider gives none.
 */
class ImportSplitwiseViewModel(
    private val groups: GroupsRepository,
    private val readFile: suspend (Uri) -> PickedFile,
    private val profileName: suspend () -> String?,
    private val online: Flow<Boolean>,
    private val saved: SavedStateHandle,
    private val io: CoroutineDispatcher,
    private val default: CoroutineDispatcher,
    private val fileName: suspend (Uri) -> String? = { null },
) : ViewModel() {
    private val ui = MutableStateFlow<ImportUiState>(ImportUiState.NoFile)

    /** The screen's state. */
    val state: StateFlow<ImportUiState> = ui.asStateFlow()

    private val effects = Effects<ImportEffect>()

    /** One-shot messages for the screen. */
    val effect: Flow<ImportEffect> = effects.flow

    /** The file shown on 29, in memory only; gone once imported or replaced. */
    private var file: SplitwiseFile? = null
    private var fileHash: String? = null
    private var readJob: Job? = null
    private var followJob: Job? = null
    private var opened = false

    init {
        saved.get<String>(IMPORTING_KEY)?.let(::follow)
    }

    /** Handles what the person did. */
    fun onEvent(event: ImportEvent) {
        when (event) {
            ImportEvent.ChooseFile -> if (ui.value !is ImportUiState.Importing && ui.value !is ImportUiState.Reading) {
                effects.send(ImportEffect.PickFile)
            }
            is ImportEvent.NameChanged -> (ui.value as? ImportUiState.Chosen)?.takeIf { !it.importing }?.let {
                ui.value = it.copy(groupName = event.name)
            }
            is ImportEvent.PersonSelected -> (ui.value as? ImportUiState.Chosen)?.takeIf { !it.importing && event.index in it.people.indices }?.let {
                ui.value = it.copy(selected = event.index)
            }
            ImportEvent.Import -> startImport()
            ImportEvent.OpenGroup -> when (val shown = ui.value) {
                is ImportUiState.Importing -> effects.send(ImportEffect.OpenGroup(shown.groupId))
                is ImportUiState.AlreadyHere -> effects.send(ImportEffect.OpenGroup(shown.groupId))
                else -> Unit
            }
            ImportEvent.Close -> {
                saved.remove<String>(IMPORTING_KEY)
                effects.send(ImportEffect.Leave)
            }
            // Back while importing does what "Open {group}" does: the import goes on by itself (5.6).
            ImportEvent.Back -> when (val shown = ui.value) {
                is ImportUiState.Importing -> effects.send(ImportEffect.OpenGroup(shown.groupId))
                is ImportUiState.Chosen -> if (!shown.importing) effects.send(ImportEffect.Leave)
                else -> effects.send(ImportEffect.Leave)
            }
        }
    }

    /**
     * The picker returned [uri]: read it, check it and show what can come across. "Reading the file" (135) shows at
     * once, before the first byte is read, so Choose file is off for the whole read; its name line fills in as soon as
     * the provider gives the name. A pick that arrives while an earlier one is still being read replaces it: the
     * earlier read is dropped and only the newest file is shown.
     */
    fun onFilePicked(uri: Uri) {
        val shown = ui.value
        if (shown is ImportUiState.Importing || (shown is ImportUiState.Chosen && shown.importing)) return
        followJob?.cancel()
        saved.remove<String>(IMPORTING_KEY)
        file = null
        fileHash = null
        readJob?.cancel()
        ui.value = ImportUiState.Reading(fileName = "")
        readJob = viewModelScope.launch {
            val known = fileName(uri)
            // A read that ends after a newer pick replaced it shows nothing, whatever the reader does when cancelled.
            ensureActive()
            if (known != null) ui.value = ImportUiState.Reading(known)
            val picked = readFile(uri)
            ensureActive()
            ui.value = ImportUiState.Reading(picked.name)
            if (!picked.readable) {
                ui.value = ImportUiState.CannotImport(picked.name, Refusal.NOT_SPLITWISE)
                return@launch
            }
            val (result, hash) = withContext(default) {
                val parsed = SplitwiseCsv.parse(picked.bytes, picked.truncated)
                parsed to (parsed as? SplitwiseResult.Ok)?.let { SplitwiseCsv.fileHash(picked.bytes) }
            }
            ui.value = when (result) {
                is SplitwiseResult.Refused -> ImportUiState.CannotImport(picked.name, result.reason)
                is SplitwiseResult.Ok -> shownOf(picked.name, result.file, requireNotNull(hash))
            }
        }
    }

    private suspend fun shownOf(name: String, read: SplitwiseFile, hash: String): ImportUiState {
        val past = withContext(io) { groups.importedFile(hash) }
        if (past != null) {
            return ImportUiState.AlreadyHere(name, read.expenseCount, read.peopleCount, past.groupId, past.groupName, past.on)
        }
        file = read
        fileHash = hash
        return ImportUiState.Chosen(
            fileName = name,
            expenseCount = read.expenseCount,
            people = read.people.mapIndexed { index, person -> PersonOption(person, read.balancesPaise[index]) },
            selected = SplitwisePeople.preselect(read.people, profileName()),
            groupName = GroupNameFromFile.of(name),
            leftOut = read.leftOutCount,
        )
    }

    private fun startImport() {
        val chosen = ui.value as? ImportUiState.Chosen ?: return
        if (!chosen.canImport) return
        val read = file ?: return
        val hash = fileHash ?: return
        val me = chosen.selected ?: return
        ui.value = chosen.copy(importing = true)
        viewModelScope.launch {
            val groupId = try {
                withContext(io) { groups.importSplitwise(read, me, chosen.groupName, hash) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                // Nobody is signed in any more: nothing was written.
                ui.value = chosen
                effects.send(ImportEffect.SignIn)
                return@launch
            } catch (_: IllegalArgumentException) {
                ui.value = chosen
                return@launch
            }
            file = null
            fileHash = null
            saved[IMPORTING_KEY] = groupId
            ui.value = ImportUiState.Importing(groupId, chosen.cleanName, sent = 0, total = read.itemCount, banner = ImportBanner.None)
            follow(groupId)
        }
    }

    /** Follows group [groupId]'s import: progress, the banner, Failed, and Group imported once it is DONE. */
    private fun follow(groupId: String) {
        followJob?.cancel()
        followJob = viewModelScope.launch {
            combine(groups.observeImport(groupId), online) { progress, isOnline -> progress to isOnline }.collect { (progress, isOnline) ->
                if (progress != null) show(progress, isOnline)
            }
        }
    }

    private fun show(progress: ImportProgress, isOnline: Boolean) {
        when (progress.state) {
            ImportState.DONE -> {
                ui.value = ImportUiState.Importing(progress.groupId, progress.groupName, progress.total, progress.total, ImportBanner.None)
                if (!opened) {
                    opened = true
                    saved.remove<String>(IMPORTING_KEY)
                    effects.send(ImportEffect.OpenImported(progress.groupId))
                }
            }
            ImportState.FAILED -> ui.value = ImportUiState.Failed(progress.groupName)
            ImportState.SENDING -> ui.value = ImportUiState.Importing(
                groupId = progress.groupId,
                groupName = progress.groupName,
                sent = progress.sent,
                total = progress.total,
                banner = when {
                    !isOnline -> ImportBanner.Offline
                    progress.problem -> ImportBanner.Problem
                    else -> ImportBanner.None
                },
            )
        }
    }

    /** Constants. */
    companion object {
        /** Saved state key of the group whose import is followed. */
        const val IMPORTING_KEY: String = "importing_group_id"
    }
}
