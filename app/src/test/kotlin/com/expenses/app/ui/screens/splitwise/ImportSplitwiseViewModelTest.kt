package com.expenses.app.ui.screens.splitwise

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.ME_USER
import com.expenses.app.groups.api.CreateGroupResponse
import com.expenses.app.groups.api.GroupDto
import com.expenses.app.groups.api.ImportResponse
import com.expenses.app.groups.error
import com.expenses.app.groups.json
import com.expenses.splitwise.Refusal
import com.expenses.splitwise.SplitwiseCsv
import com.expenses.splitwise.SplitwiseResult
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Import from Splitwise's states and moves (docs/splitwise-import.md 5.7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImportSplitwiseViewModelTest {
    private val env = GroupsTestEnv()
    private val online = MutableStateFlow(true)
    private val files = HashMap<Uri, PickedFile>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private val csv = """
        Date,Description,Category,Cost,Currency,Arjun Mehta,Priya Nair,Kiran Rao
        2026-11-01,Rent,Rent,3000.00,INR,-1000.00,2000.00,-1000.00
        2026-11-10,Swiggy,Dining out,450.00,INR,300.00,-150.00,-150.00
        2026-11-12,Kiran paid Priya,Payment,500.00,INR,0.00,-500.00,500.00
        2026-11-13,Coffee,General,4.00,USD,2.00,-2.00,0.00
    """.trimIndent()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle(), profile: String? = "Arjun Mehta") = ImportSplitwiseViewModel(
        groups = env.groups,
        readFile = { files.getValue(it) },
        profileName = { profile },
        online = online,
        saved = saved,
        io = Dispatchers.IO,
        default = Dispatchers.Default,
    )

    private fun ImportSplitwiseViewModel.pick(name: String, text: String = csv, truncated: Boolean = false, readable: Boolean = true) {
        val uri = Uri.parse("content://test/${files.size}/$name")
        files[uri] = PickedFile(name, text.toByteArray(), truncated, readable)
        onFilePicked(uri)
    }

    private suspend fun ImportSplitwiseViewModel.shown(): ImportUiState =
        state.first { it != ImportUiState.NoFile && it !is ImportUiState.Reading }

    private suspend fun ImportSplitwiseViewModel.chosen(): ImportUiState.Chosen = shown() as ImportUiState.Chosen

    private suspend inline fun <reified T : ImportUiState> ImportSplitwiseViewModel.until(): T = state.first { it is T } as T

    /** The server takes the group's create and its one part (3 items) with [part]. */
    private fun server(groupId: String, part: MockResponse = json(200, ImportResponse.serializer(), ImportResponse(true, 2, 4))) {
        env.fake.on(
            "POST", "/v1/groups",
            json(201, CreateGroupResponse.serializer(), CreateGroupResponse(GroupDto(groupId, "group", "Flat 302", ME_USER, 1, 0), emptyList())),
        )
        env.fake.on("POST", "/v1/groups/$groupId/import", part)
    }

    @Test fun chooseFileOpensThePicker() = runTest {
        val vm = viewModel()
        assertEquals(ImportUiState.NoFile, vm.state.value)
        vm.onEvent(ImportEvent.ChooseFile)
        assertEquals(ImportEffect.PickFile, vm.effect.first())
    }

    @Test fun aGoodFileShowsItsNamePeopleAndCounts() = runTest {
        val vm = viewModel()
        vm.pick("flat-302-export.csv")
        val chosen = vm.chosen()
        assertEquals("flat-302-export.csv", chosen.fileName)
        assertEquals(2, chosen.expenseCount)
        assertEquals(
            listOf(PersonOption("Arjun Mehta", -70_000), PersonOption("Priya Nair", 135_000), PersonOption("Kiran Rao", -65_000)),
            chosen.people,
        )
        assertEquals(0, chosen.selected)
        assertEquals("Flat 302", chosen.groupName)
        assertEquals(1, chosen.leftOut)
        assertTrue(chosen.canImport)
    }

    @Test fun theProfileNamePreselectsOnlyOnAUniqueMatch() = runTest {
        // The first word matches when the whole name does not.
        assertEquals(0, viewModel(profile = "arjun kumar").apply { pick("a.csv") }.chosen().selected)
        // No match: nobody is chosen, and Import is off (133).
        val none = viewModel(profile = "Rahul Menon").apply { pick("b.csv") }.chosen()
        assertNull(none.selected)
        assertFalse(none.canImport)
        // Two people with the same first name: nobody.
        val twoPriyas = csv.replace("Arjun Mehta", "Priya Shah")
        assertNull(viewModel(profile = "Priya").apply { pick("c.csv", twoPriyas) }.chosen().selected)
    }

    @Test fun importNeedsSomeoneChosenAndAName() = runTest {
        val vm = viewModel(profile = null)
        vm.pick("export.csv")
        val chosen = vm.chosen()
        // A generic file name gives no group name.
        assertEquals("", chosen.groupName)
        assertNull(chosen.selected)
        assertFalse(chosen.canImport)
        vm.onEvent(ImportEvent.Import)
        vm.onEvent(ImportEvent.PersonSelected(1))
        assertFalse(vm.chosen().canImport)
        vm.onEvent(ImportEvent.NameChanged("   "))
        assertFalse(vm.chosen().canImport)
        vm.onEvent(ImportEvent.Import)
        vm.onEvent(ImportEvent.PersonSelected(7))
        assertEquals(1, vm.chosen().selected)
        vm.onEvent(ImportEvent.NameChanged("Goa trip"))
        assertTrue(vm.chosen().canImport)
        // Nothing was written by the taps while Import was off.
        assertTrue(env.db.groupDao().allGroups().isEmpty())
    }

    @Test fun filesThatCannotComeAcrossSayWhy() = runTest {
        val vm = viewModel()
        vm.pick("expenses-2026.pdf", "%PDF-1.7 binary")
        assertEquals(ImportUiState.CannotImport("expenses-2026.pdf", Refusal.NOT_SPLITWISE), vm.shown())
        vm.pick("big.csv", csv, truncated = true)
        assertEquals(ImportUiState.CannotImport("big.csv", Refusal.TOO_LARGE_FILE), vm.state.first { it is ImportUiState.CannotImport && it.fileName == "big.csv" })
        vm.pick("gone.csv", csv, readable = false)
        assertEquals(ImportUiState.CannotImport("gone.csv", Refusal.NOT_SPLITWISE), vm.state.first { it is ImportUiState.CannotImport && it.fileName == "gone.csv" })
        val oneCurrency = "Date,Description,Category,Cost,Currency,A,B\n2026-11-01,Cab,Taxi,4.00,USD,2.00,-2.00"
        vm.pick("usd.csv", oneCurrency)
        assertEquals(Refusal.NOTHING_IN_RUPEES, (vm.state.first { it is ImportUiState.CannotImport && it.fileName == "usd.csv" } as ImportUiState.CannotImport).reason)
    }

    @Test fun theSameFileImportedBeforeIsAlreadyHere() = runTest {
        val read = (SplitwiseCsv.parse(csv.toByteArray()) as SplitwiseResult.Ok).file
        val groupId = env.groups.importSplitwise(read, 0, "Flat 302", SplitwiseCsv.fileHash(csv.toByteArray()))
        val vm = viewModel()
        vm.pick("flat-302-export (1).csv")
        val here = vm.shown() as ImportUiState.AlreadyHere
        assertEquals(groupId, here.groupId)
        assertEquals("Flat 302", here.groupName)
        assertEquals(LocalDate.of(2026, 11, 16), here.on)
        assertEquals(2, here.expenseCount)
        assertEquals(3, here.peopleCount)
        vm.onEvent(ImportEvent.OpenGroup)
        assertEquals(ImportEffect.OpenGroup(groupId), vm.effect.first())
        // Another file (one row changed) is not the same.
        vm.pick("other.csv", csv.replace("2026-11-01", "2026-11-02"))
        assertEquals("other.csv", (vm.state.first { it is ImportUiState.Chosen } as ImportUiState.Chosen).fileName)
    }

    @Test fun importShowsProgressAndOpensGroupImportedWhenDone() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.pick("flat-302-export.csv")
        vm.chosen()
        vm.onEvent(ImportEvent.Import)
        val importing = vm.until<ImportUiState.Importing>()
        assertEquals("Flat 302", importing.groupName)
        assertEquals(0, importing.sent)
        assertEquals(3, importing.total)
        assertEquals(ImportBanner.None, importing.banner)
        assertEquals(importing.groupId, saved.get<String>(ImportSplitwiseViewModel.IMPORTING_KEY))
        // Back while importing opens the group, as "Open Flat 302" does.
        vm.onEvent(ImportEvent.Back)
        assertEquals(ImportEffect.OpenGroup(importing.groupId), vm.effect.first())

        server(importing.groupId)
        env.engine.push()

        assertEquals(ImportEffect.OpenImported(importing.groupId), vm.effect.first())
        assertNull(saved.get<String>(ImportSplitwiseViewModel.IMPORTING_KEY))
    }

    @Test fun offlineAndServerTroubleShowTheirBanners() = runTest {
        val vm = viewModel()
        vm.pick("flat-302-export.csv")
        vm.chosen()
        vm.onEvent(ImportEvent.Import)
        val importing = vm.until<ImportUiState.Importing>()
        online.value = false
        assertEquals(ImportBanner.Offline, (vm.state.first { (it as? ImportUiState.Importing)?.banner == ImportBanner.Offline } as ImportUiState.Importing).banner)
        online.value = true
        server(importing.groupId, error(503, "internal"))
        env.engine.push()
        val problem = vm.state.first { (it as? ImportUiState.Importing)?.banner == ImportBanner.Problem } as ImportUiState.Importing
        assertEquals(0, problem.sent)
    }

    @Test fun aRefusedPartEndsInFailedAndCloseLeaves() = runTest {
        val vm = viewModel()
        vm.pick("flat-302-export.csv")
        vm.chosen()
        vm.onEvent(ImportEvent.Import)
        val importing = vm.until<ImportUiState.Importing>()
        server(importing.groupId, error(400, "bad_request"))
        env.fake.on("DELETE", "/v1/groups/${importing.groupId}", MockResponse().setResponseCode(204))
        env.engine.push()
        assertEquals(ImportUiState.Failed("Flat 302"), vm.until<ImportUiState.Failed>())
        vm.onEvent(ImportEvent.Close)
        assertEquals(ImportEffect.Leave, vm.effect.first())
    }

    @Test fun aStartedImportIsFollowedAgainAfterTheProcessWasStopped() = runTest {
        val read = (SplitwiseCsv.parse(csv.toByteArray()) as SplitwiseResult.Ok).file
        val groupId = env.groups.importSplitwise(read, 0, "Flat 302", "ab".repeat(32))
        val vm = viewModel(SavedStateHandle(mapOf(ImportSplitwiseViewModel.IMPORTING_KEY to groupId)))
        val importing = vm.until<ImportUiState.Importing>()
        assertEquals(groupId, importing.groupId)
        assertEquals(3, importing.total)
    }

    @Test fun backLeavesWhenNothingIsImporting() = runTest {
        val vm = viewModel()
        vm.onEvent(ImportEvent.Back)
        assertEquals(ImportEffect.Leave, vm.effect.first())
    }

    /** A view model whose read of [slow] ends only when [read] does, even after a newer pick dropped it. */
    private fun slowViewModel(slow: Uri, read: CompletableDeferred<PickedFile>, name: String?) = ImportSplitwiseViewModel(
        groups = env.groups,
        readFile = { if (it == slow) withContext(NonCancellable) { read.await() } else files.getValue(it) },
        profileName = { "Arjun Mehta" },
        online = online,
        saved = SavedStateHandle(),
        io = Dispatchers.IO,
        default = Dispatchers.Default,
        fileName = { if (it == slow) name else null },
    )

    @Test fun readingShowsBeforeTheFileIsReadWithItsNameAndChooseFileOff() = runTest {
        val slow = Uri.parse("content://test/documents/17")
        val read = CompletableDeferred<PickedFile>()
        val vm = slowViewModel(slow, read, name = "flat-302-export.csv")
        vm.onFilePicked(slow)
        // 135 while the bytes are still on their way, named as soon as the provider said the name.
        assertEquals(ImportUiState.Reading("flat-302-export.csv"), vm.state.value)
        // Choose file is off: no picker opens. Back still leaves.
        vm.onEvent(ImportEvent.ChooseFile)
        vm.onEvent(ImportEvent.Back)
        assertEquals(ImportEffect.Leave, vm.effect.first())
        read.complete(PickedFile("flat-302-export.csv", csv.toByteArray(), truncated = false))
        assertEquals("flat-302-export.csv", vm.chosen().fileName)
    }

    @Test fun readingShowsWithItsNameLineEmptyWhileTheProviderGivesNoName() = runTest {
        val slow = Uri.parse("content://test/documents/18")
        val read = CompletableDeferred<PickedFile>()
        val vm = slowViewModel(slow, read, name = null)
        vm.onFilePicked(slow)
        assertEquals(ImportUiState.Reading(""), vm.state.value)
        read.complete(PickedFile("notes.txt", "hello".toByteArray(), truncated = false))
        assertEquals(ImportUiState.CannotImport("notes.txt", Refusal.NOT_SPLITWISE), vm.shown())
    }

    @Test fun aSecondPickReplacesTheFirstEvenWhenTheFirstReadEndsLater() = runTest {
        val slow = Uri.parse("content://test/documents/19")
        val read = CompletableDeferred<PickedFile>()
        val vm = slowViewModel(slow, read, name = "old-export.csv")
        vm.onFilePicked(slow)
        assertEquals(ImportUiState.Reading("old-export.csv"), vm.state.value)
        // The picker's result for another file arrives while the first is still read: only the newest is shown.
        vm.pick("flat-302-export.csv")
        assertEquals("flat-302-export.csv", vm.chosen().fileName)
        // The first read ends now. It was dropped: neither its name nor its refusal shows, and Import still works on
        // the second file.
        read.complete(PickedFile("old-export.csv", "not a spreadsheet".toByteArray(), truncated = false))
        val chosen = vm.state.value as ImportUiState.Chosen
        assertEquals("flat-302-export.csv", chosen.fileName)
        assertTrue(chosen.canImport)
    }
}
