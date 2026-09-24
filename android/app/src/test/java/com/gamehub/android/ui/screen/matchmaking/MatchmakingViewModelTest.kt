package com.gamehub.android.ui.screen.matchmaking

import app.cash.turbine.test
import com.gamehub.android.data.remote.MatchmakingTicket
import com.gamehub.android.data.repository.MatchmakingRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.ErrorKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel tests, with no Android framework involved.
 *
 * The ViewModel depends only on a repository interface, so it can be
 * constructed directly with a mock. That is the payoff for constructor
 * injection: no Robolectric, no instrumentation, no emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchmakingViewModelTest {

    private val repository: MatchmakingRepository = mockk(relaxed = true)
    private val dispatcher = StandardTestDispatcher()

    private lateinit var viewModel: MatchmakingViewModel

    @Before
    fun setUp() {
        // viewModelScope is hardwired to Dispatchers.Main, which does not
        // exist in a plain JVM test. Replacing it is what makes these tests
        // runnable at all, and StandardTestDispatcher keeps them
        // deterministic by not running coroutines until advanced.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts idle`() = runTest(dispatcher) {
        viewModel = MatchmakingViewModel(repository)

        assertEquals(MatchmakingState.Idle, viewModel.state.value)
    }

    @Test
    fun `join moves to searching and begins polling`() = runTest(dispatcher) {
        val ticket = waitingTicket(waitedSeconds = 0)
        coEvery { repository.join(any(), any()) } returns ApiResult.Success(ticket)
        every { repository.pollTicket(any()) } returns flowOf(ApiResult.Success(ticket))

        viewModel = MatchmakingViewModel(repository)

        viewModel.state.test {
            assertEquals(MatchmakingState.Idle, awaitItem())

            viewModel.join(GAME_ID)

            assertEquals(MatchmakingState.Joining, awaitItem())
            assertTrue(awaitItem() is MatchmakingState.Searching)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a duplicate join is not shown as an error`() = runTest(dispatcher) {
        // The server enforces one active ticket per player with a partial
        // unique index. A 409 here almost always means a retried request that
        // had already succeeded, so the player is in the queue and showing an
        // error would be actively misleading.
        coEvery { repository.join(any(), any()) } returns
            ApiResult.Failure(ErrorKind.CLIENT, code = "ALREADY_QUEUED")

        viewModel = MatchmakingViewModel(repository)

        viewModel.state.test {
            awaitItem()
            viewModel.join(GAME_ID)
            awaitItem() // Joining

            assertEquals(MatchmakingState.AlreadyQueued, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `joining twice does not send a second request`() = runTest(dispatcher) {
        // A double-tap. The unique index would reject the duplicate anyway,
        // so this only avoids a request that is certain to fail.
        val ticket = waitingTicket(waitedSeconds = 0)
        coEvery { repository.join(any(), any()) } returns ApiResult.Success(ticket)
        every { repository.pollTicket(any()) } returns flowOf(ApiResult.Success(ticket))

        viewModel = MatchmakingViewModel(repository)

        viewModel.join(GAME_ID)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.join(GAME_ID)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.join(any(), any()) }
    }

    @Test
    fun `a matched ticket ends the search`() = runTest(dispatcher) {
        val waiting = waitingTicket(waitedSeconds = 3)
        val matched = waiting.copy(status = "MATCHED", matchId = "match-1")

        coEvery { repository.join(any(), any()) } returns ApiResult.Success(waiting)
        every { repository.pollTicket(any()) } returns
            flowOf(ApiResult.Success(waiting), ApiResult.Success(matched))

        viewModel = MatchmakingViewModel(repository)
        viewModel.join(GAME_ID)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is MatchmakingState.Matched)
        assertEquals("match-1", (state as MatchmakingState.Matched).ticket.matchId)
    }

    @Test
    fun `an expired ticket is distinct from a failure`() = runTest(dispatcher) {
        // Nothing went wrong: the bucket had too few players. The UI offers a
        // different action, so conflating this with an error would send the
        // player down the wrong path.
        val waiting = waitingTicket(waitedSeconds = 300)
        val expired = waiting.copy(status = "EXPIRED")

        coEvery { repository.join(any(), any()) } returns ApiResult.Success(waiting)
        every { repository.pollTicket(any()) } returns flowOf(ApiResult.Success(expired))

        viewModel = MatchmakingViewModel(repository)
        viewModel.join(GAME_ID)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(MatchmakingState.Expired, viewModel.state.value)
    }

    @Test
    fun `a transient poll failure does not drop the player from the queue`() = runTest(dispatcher) {
        // The ticket still exists server-side. Cancelling the search because
        // one poll timed out would lose the queue position for no reason.
        val waiting = waitingTicket(waitedSeconds = 5)

        coEvery { repository.join(any(), any()) } returns ApiResult.Success(waiting)
        every { repository.pollTicket(any()) } returns flowOf(
            ApiResult.Success(waiting),
            ApiResult.Failure(ErrorKind.SERVER),
        )

        viewModel = MatchmakingViewModel(repository)
        viewModel.join(GAME_ID)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is MatchmakingState.Searching)
    }

    @Test
    fun `leaving returns to idle even when the request fails`() = runTest(dispatcher) {
        // Leaving is idempotent. If the request failed the ticket expires on
        // its own TTL, and stranding the player on a searching screen would
        // be worse than optimistically clearing it.
        coEvery { repository.leave() } returns ApiResult.Failure(ErrorKind.OFFLINE)

        viewModel = MatchmakingViewModel(repository)
        viewModel.leave()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(MatchmakingState.Idle, viewModel.state.value)
    }

    private fun waitingTicket(waitedSeconds: Long) = MatchmakingTicket(
        ticketId = "ticket-1",
        status = "WAITING",
        gameId = GAME_ID,
        region = "EU_WEST",
        skillRating = 1200,
        enqueuedAt = "2026-09-22T10:00:00Z",
        expiresAt = "2026-09-22T10:05:00Z",
        waitedSeconds = waitedSeconds,
    )

    private companion object {
        const val GAME_ID = "a0000000-0000-4000-8000-000000000003"
    }
}
