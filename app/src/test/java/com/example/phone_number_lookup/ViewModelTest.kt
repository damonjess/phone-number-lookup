package com.example.phone_number_lookup

import com.example.phone_number_lookup.ui.viewmodels.GitHubOsintViewModel
import com.example.phone_number_lookup.ui.viewmodels.PhoneLookupViewModel
import com.example.phone_number_lookup.ui.viewmodels.ScanCoordinator
import com.example.phone_number_lookup.ui.viewmodels.UsernameAndEmailOsintViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelTest {

    @Test
    fun phoneLookupViewModel_updatesInputAndClearsState() {
        val viewModel = PhoneLookupViewModel(android.app.Application())
        assertEquals("", viewModel.uiState.value.phoneNumberInput)

        viewModel.onPhoneNumberInputChanged("01782 447307")
        assertEquals("01782 447307", viewModel.uiState.value.phoneNumberInput)

        viewModel.clear()
        assertEquals("", viewModel.uiState.value.phoneNumberInput)
        assertNull(viewModel.uiState.value.resultNumber)
    }

    @Test
    fun usernameAndEmailOsintViewModel_handlesSubTabsAndScanDepth() {
        val viewModel = UsernameAndEmailOsintViewModel()
        assertEquals(0, viewModel.uiState.value.subTabState)
        assertEquals(0, viewModel.uiState.value.scanDepth)

        viewModel.onTargetInputChanged("alice")
        assertEquals("alice", viewModel.uiState.value.targetInput)

        viewModel.onSubTabChanged(1)
        assertEquals(1, viewModel.uiState.value.subTabState)

        viewModel.onScanDepthChanged(2)
        assertEquals(2, viewModel.uiState.value.scanDepth)

        viewModel.clear()
        assertEquals("", viewModel.uiState.value.targetInput)
        assertEquals(1, viewModel.uiState.value.subTabState)
        assertEquals(2, viewModel.uiState.value.scanDepth)
    }

    @Test
    fun gitHubOsintViewModel_updatesQueryAndClearsState() {
        val viewModel = GitHubOsintViewModel()
        assertEquals("", viewModel.uiState.value.query)

        viewModel.onQueryChanged("torvalds")
        assertEquals("torvalds", viewModel.uiState.value.query)

        viewModel.clear()
        assertEquals("", viewModel.uiState.value.query)
        assertNull(viewModel.uiState.value.profile)
    }

    // ------------------------------------------------------- scan coordination

    @Test
    fun aSecondScanMakesTheFirstOneStale() {
        val coordinator = ScanCoordinator(CoroutineScope(Dispatchers.Unconfined))

        val first = coordinator.supersede()
        assertTrue("the first run starts out current", coordinator.isCurrent(first))

        val second = coordinator.supersede()
        assertFalse("starting a new scan must retire the old one", coordinator.isCurrent(first))
        assertTrue(coordinator.isCurrent(second))
    }

    @Test
    fun aSupersededScanCannotPublishItsResults() {
        // Reproduces the on-device bug: a slow full sweep for "blue" was overtaken by a
        // quick "qzwx" scan, then finished last and replaced qzwx's results (563 profiles
        // belonging to blue) while the title still said qzwx.
        val coordinator = ScanCoordinator(CoroutineScope(Dispatchers.Unconfined))
        var displayed: String? = null

        val slowBlue = coordinator.supersede()
        val fastQzwx = coordinator.supersede()

        // The quick scan finishes first and publishes.
        runBlocking {
            coordinator.start(fastQzwx) { active ->
                if (coordinator.isCurrent(active)) displayed = "qzwx results"
            }
        }
        assertEquals("qzwx results", displayed)

        // The slow scan finishes later. It must refuse to publish.
        runBlocking {
            coordinator.start(slowBlue) { active ->
                if (coordinator.isCurrent(active)) displayed = "blue results (563 profiles)"
            }
        }
        assertEquals("a superseded scan must not overwrite newer results", "qzwx results", displayed)
    }

    @Test
    fun cancelAllRetiresEveryOutstandingScan() {
        val coordinator = ScanCoordinator(CoroutineScope(Dispatchers.Unconfined))

        val token = coordinator.supersede()
        assertTrue(coordinator.isCurrent(token))

        // Leaving the screen or clearing the form must invalidate in-flight work, because
        // the UI state it was going to write to is being discarded anyway.
        coordinator.cancelAll()
        assertFalse(coordinator.isCurrent(token))

        val fresh = coordinator.supersede()
        assertTrue(coordinator.isCurrent(fresh))
    }

    @Test
    fun jobsBelongingToTheSameScanAreCountedTogether() {
        val coordinator = ScanCoordinator(CoroutineScope(Dispatchers.Unconfined))

        // The email scan runs three lookups (registration, breaches, handle footprint)
        // under one token so none of them cancels the others.
        val token = coordinator.supersede()
        repeat(3) { coordinator.start(token) { } }

        assertEquals(3, coordinator.activeCount)
        assertTrue(coordinator.isCurrent(token))

        coordinator.supersede()
        assertEquals("a new scan clears the previous jobs", 0, coordinator.activeCount)
    }
}
