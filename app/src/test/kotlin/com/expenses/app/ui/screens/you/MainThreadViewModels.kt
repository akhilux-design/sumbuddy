package com.expenses.app.ui.screens.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A real main thread for the ViewModel tests of Account and Your name, as the Group page tests have one:
 * [Dispatchers.Main] runs on a thread of its own, and the ViewModels made with [kept] are cleared before it goes.
 *
 * [finish] clears them and waits until their coroutines have ended (a database query or a save already on its way
 * ends first), then closes what answers them (the fake server, the database), waits until no call is on its way and
 * the main thread has run everything handed to it, and only then resets Main. A late answer that resumed into Main
 * while `resetMain` swapped it would fail with "Dispatchers.Main is used concurrently with setting it" and spoil Main
 * for every later test in the JVM; a query cut off by the database closing would fail the next test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainThreadViewModels {
    private val thread = Executors.newSingleThreadExecutor()
    private val main = thread.asCoroutineDispatcher()
    private val store = ViewModelStore()
    private val stores = CopyOnWriteArrayList(listOf(store))
    private val made = CopyOnWriteArrayList<ViewModel>()

    /** Makes the thread Dispatchers.Main; from `@Before`. */
    fun start() = Dispatchers.setMain(main)

    /**
     * The ViewModel [make] gives, kept in [owner] (a test that clears a store itself, to close the screen, passes its
     * own) and cleared by [finish] at the latest.
     */
    fun <T : ViewModel> kept(type: Class<T>, owner: ViewModelStore, make: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
        }
        stores.addIfAbsent(owner)
        return ViewModelProvider(owner, factory)["vm${made.size}", type].also { made += it }
    }

    /** Makes a [T] with [make] in [owner], cleared by [finish] at the latest. */
    inline fun <reified T : ViewModel> kept(owner: ViewModelStore? = null, noinline make: () -> T): T =
        kept(T::class.java, owner ?: defaultStore(), make)

    /** The store [kept] uses when given none. */
    fun defaultStore(): ViewModelStore = store

    /** Waits until the main thread has run everything handed to it. */
    fun drain() = repeat(DRAIN_ROUNDS) {
        thread.submit {}.get()
        Thread.sleep(DRAIN_PAUSE_MS)
    }

    /**
     * From `@After`: clears the ViewModels on the main thread and waits (up to 5 s) until their coroutines ended, runs
     * [close], waits (up to 5 s) until [idle] (no call on its way), drains the main thread, then resets Main and ends
     * the thread.
     */
    fun finish(close: () -> Unit, idle: () -> Boolean) {
        runBlocking(main) { stores.forEach { it.clear() } }
        runBlocking { withTimeoutOrNull(WAIT_MS) { made.forEach { it.viewModelScope.coroutineContext.job.join() } } }
        close()
        val giveUpAt = System.currentTimeMillis() + WAIT_MS
        while (!idle() && System.currentTimeMillis() < giveUpAt) Thread.sleep(DRAIN_PAUSE_MS)
        drain()
        Dispatchers.resetMain()
        main.close()
    }

    private companion object {
        const val DRAIN_ROUNDS = 3
        const val DRAIN_PAUSE_MS = 20L
        const val WAIT_MS = 5_000L
    }
}
