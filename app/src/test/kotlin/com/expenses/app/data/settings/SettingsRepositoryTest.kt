package com.expenses.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    private val folder: File = Files.createTempDirectory("settings").toFile()

    @After fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun TestScope.repository() = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder, "settings.preferences_pb") },
    )

    @Test fun defaultsBeforeOnboarding() = runTest {
        val settings = repository().settings.first()
        assertNull(settings.userName)
        assertFalse(settings.onboardingDone)
        assertNull(settings.trackingMode)
        assertNull(settings.trackingStartAt)
        assertNull(settings.lastScanAt)
        assertTrue(settings.themeFollowsSystem)
    }

    @Test fun storesEverySetting() = runTest {
        val repo = repository()
        val start = Instant.parse("2026-10-31T18:30:00Z")
        val scan = Instant.parse("2026-11-16T06:30:00Z")
        repo.setUserName("  Arjun ")
        repo.setTrackingMode(TrackingMode.SMS)
        repo.setTrackingStart(start)
        repo.setLastScan(scan)
        repo.setThemeFollowsSystem(false)
        repo.completeOnboarding()

        assertEquals(
            AppSettings(
                userName = "Arjun",
                onboardingDone = true,
                trackingMode = TrackingMode.SMS,
                trackingStartAt = start,
                lastScanAt = scan,
                themeFollowsSystem = false,
            ),
            repo.settings.first(),
        )

        repo.setUserName(" ")
        assertNull(repo.settings.first().userName)
    }
}
