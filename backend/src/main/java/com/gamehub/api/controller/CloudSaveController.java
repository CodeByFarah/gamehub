package com.gamehub.api.controller;

import com.gamehub.api.dto.CloudSaveResponse;
import com.gamehub.api.dto.CloudSaveUploadRequest;
import com.gamehub.api.security.CurrentUser;
import com.gamehub.application.service.CloudSaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Cloud save upload, download and listing.
 *
 * <h2>The 409 is part of the contract</h2>
 * A conflict is a normal outcome here, not an error condition, and the
 * response carries the server current version and checksum so the client can
 * merge without another round trip. Documented on the endpoint because a
 * client that treats 409 as a generic failure will lose player progress.
 */
@Tag(name = "Cloud saves")
@Validated
@RestController
@RequestMapping("/api/cloud-saves")
@RequiredArgsConstructor
public class CloudSaveController {

    private final CloudSaveService cloudSaves;

    @Operation(summary = "List the saves for the authenticated player, metadata only")
    @GetMapping
    public List<CloudSaveResponse> list(@CurrentUser UUID userId) {
        return cloudSaves.listFor(userId);
    }

    @Operation(summary = "Download one save including its payload")
    @GetMapping("/{gameId}")
    public CloudSaveResponse download(@CurrentUser UUID userId,
                                      @PathVariable UUID gameId,
                                      @RequestParam(defaultValue = "0") @Min(0) @Max(9) int slot) {
        return cloudSaves.download(userId, gameId, (short) slot);
    }

    @Operation(summary = "Create or update a save under optimistic concurrency control")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accepted, version incremented"),
            @ApiResponse(responseCode = "400",
                    description = "Payload is not valid base64, is empty, exceeds 1 MiB, or "
                            + "its checksum does not match the decoded bytes"),
            @ApiResponse(responseCode = "409",
                    description = "Version conflict. The meta object carries serverVersion and "
                            + "serverChecksum so the client can merge and retry.")
    })
    @PostMapping
    public CloudSaveResponse upload(@CurrentUser UUID userId,
                                    @Valid @RequestBody CloudSaveUploadRequest request) {
        return cloudSaves.upload(userId, request);
    }
}
