package com.gamehub.api.controller;

import com.gamehub.api.dto.GameDetailResponse;
import com.gamehub.api.dto.GameSummaryResponse;
import com.gamehub.api.dto.PageResponse;
import com.gamehub.api.error.GlobalExceptionHandler;
import com.gamehub.application.exception.ResourceNotFoundException;
import com.gamehub.api.security.JwtService;
import com.gamehub.application.service.GameCatalogService;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for the catalogue endpoints.
 *
 * <h2>What this tier is for</h2>
 * Request mapping, parameter binding, validation, status codes and the error
 * envelope. Everything below the controller is mocked, so a failure here is
 * unambiguously an API-contract problem rather than a service bug.
 *
 * <p>{@code addFilters = false} disables the security filter chain. These
 * endpoints are public, and leaving the chain in would make every test also a
 * test of JWT parsing, which is covered elsewhere.
 *
 * <p>{@link GlobalExceptionHandler} is imported explicitly. Without it these
 * tests would assert against the default Spring error page, a shape the real
 * application never returns.
 */
@WebMvcTest(GameController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
// SecurityProperties.jwtSecret has no default, so the context refuses to
// start without one. That is the fail-fast design working, not a test
// inconvenience: a development default would eventually reach production.
// The value here is public, worthless, and only signs nothing.
@TestPropertySource(properties = {
        "gamehub.security.jwt-secret=web-slice-test-key-not-a-secret-0123456789",
})
class GameControllerTest {

    private static final UUID GAME_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private GameCatalogService catalogue;

    /**
     * JwtAuthenticationFilter is a @Component, so the slice still constructs
     * it even though addFilters = false stops it running. Mocking its one
     * dependency satisfies the context without pulling in real security.
     */
    @MockitoBean private JwtService jwtService;

    /** Required by GlobalExceptionHandler to stamp a trace id on every error. */
    @MockitoBean private Tracer tracer;

    private static GameSummaryResponse summary() {
        return new GameSummaryResponse(
                GAME_ID, "stellar-drift", "Stellar Drift", "Zero-gravity racing",
                "Racing", List.of("competitive"), null, new BigDecimal("4.60"),
                18420, 9800, true, true, 12);
    }

    @Nested
    @DisplayName("browse")
    class Browse {

        @Test
        @DisplayName("returns the page envelope, not a bare array")
        void returnsPageEnvelope() throws Exception {
            given(catalogue.browse(anyInt(), anyInt()))
                    .willReturn(PageResponse.of(List.of(summary()), 0, 20, 1));

            mockMvc.perform(get("/api/games"))
                    .andExpect(status().isOk())
                    // The envelope is the contract. A bare array would leave
                    // the client no way to page.
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.items[0].title").value("Stellar Drift"))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        @DisplayName("a query routes to search rather than browse")
        void queryRoutesToSearch() throws Exception {
            given(catalogue.search(anyString(), anyInt(), anyInt()))
                    .willReturn(List.of(summary()));

            mockMvc.perform(get("/api/games").param("q", "stellar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].slug").value("stellar-drift"))
                    // Search cannot cheaply produce a total, and reports that
                    // honestly rather than inventing one.
                    .andExpect(jsonPath("$.totalItems").value(-1));
        }

        @Test
        @DisplayName("filters route to the structured query")
        void filtersRouteToFilter() throws Exception {
            given(catalogue.filter(anyString(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .willReturn(List.of(summary()));

            mockMvc.perform(get("/api/games")
                            .param("genre", "Racing")
                            .param("multiplayer", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].supportsMultiplayer").value(true));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a page size above the cap is rejected, not silently clamped")
        void rejectsOversizedPage() throws Exception {
            // Silently clamping would let a client believe it asked for 500
            // and received 500, when it actually received 50.
            mockMvc.perform(get("/api/games").param("size", "500"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("a negative page is rejected")
        void rejectsNegativePage() throws Exception {
            mockMvc.perform(get("/api/games").param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("a malformed UUID is a 400, not a 500")
        void rejectsMalformedId() throws Exception {
            mockMvc.perform(get("/api/games/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        }
    }

    @Nested
    @DisplayName("error envelope")
    class ErrorEnvelope {

        @Test
        @DisplayName("a missing game returns the documented 404 shape")
        void notFoundUsesTheEnvelope() throws Exception {
            willThrow(new ResourceNotFoundException("game", GAME_ID))
                    .given(catalogue).detail(GAME_ID);

            mockMvc.perform(get("/api/games/" + GAME_ID))
                    .andExpect(status().isNotFound())
                    // Every field a client depends on. The code is what
                    // clients branch on, so changing it is a breaking change
                    // even though it reads like prose.
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.path").value("/api/games/" + GAME_ID))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("an unexpected failure returns 500 and leaks nothing")
        void unexpectedFailureLeaksNothing() throws Exception {
            willThrow(new IllegalStateException("jdbc://user:hunter2@db.internal/gamehub"))
                    .given(catalogue).detail(GAME_ID);

            mockMvc.perform(get("/api/games/" + GAME_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                    // The internal message must never reach the client: a
                    // driver exception can carry a connection string.
                    .andExpect(jsonPath("$.message")
                            .value("an unexpected error occurred, quote the trace id when reporting it"))
                    .andExpect(jsonPath("$.trace").doesNotExist());
        }
    }

    @Test
    @DisplayName("detail by slug is served, for shareable links")
    void detailBySlug() throws Exception {
        given(catalogue.detailBySlug("stellar-drift")).willReturn(
                new GameDetailResponse(GAME_ID, "stellar-drift", "Stellar Drift",
                        "Zero-gravity racing", null, "Racing", List.of(), 1, 8, 12,
                        true, true, null, null, new BigDecimal("4.60"), 18420, 9800, null));

        mockMvc.perform(get("/api/games/slug/stellar-drift"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Stellar Drift"));
    }
}
