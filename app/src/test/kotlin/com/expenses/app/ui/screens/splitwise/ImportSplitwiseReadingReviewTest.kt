package com.expenses.app.ui.screens.splitwise

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.expenses.app.groups.GroupsTestEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review (2026-11-19): "Reading the file" (02-everyday/135) shows while the picked file is read on the phone
 * (docs/splitwise-import.md 5.7: "A file picked goes to Reading, then SplitwiseCsv.parse"; design flow note: "'Reading
 * the file' shows while the file is read on the phone"), with Choose file off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ImportSplitwiseReadingReviewTest {
    private val env = GroupsTestEnv()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    /**
     * A 5 MB export picked from Google Drive or WhatsApp's documents is read through the provider, which can take
     * seconds. Until [readPicked] returns, the screen still shows 28 ("No file chosen yet") with Choose file on, and a
     * second pick cancels the first; 135 only covers the parse that follows.
     */
    @Test fun readingShowsWhileThePickedFileIsStillBeingRead() = runTest {
        val read = CompletableDeferred<PickedFile>()
        val vm = ImportSplitwiseViewModel(
            groups = env.groups,
            readFile = { read.await() },
            profileName = { null },
            online = MutableStateFlow(true),
            saved = SavedStateHandle(),
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )
        vm.onFilePicked(Uri.parse("content://test/1/flat-302-export.csv"))
        val shown = vm.state.value
        read.complete(PickedFile("flat-302-export.csv", ByteArray(0), truncated = false, readable = false))
        assertTrue("shown while reading: $shown", shown is ImportUiState.Reading)
    }
}
