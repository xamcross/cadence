package com.cadence.api;

import com.cadence.service.CsvImportService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * F42 CSV import API (contracts/import-api.md). Class-level {@code @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")}
 * is the single source of truth for the role and satisfies the F02 RbacEndpointInventoryTest for every handler.
 * Under the internal (non-allow-listed) prefix so a missing declaration would fail that test by design.
 *
 * <p>Upload returns 202 immediately (no parse on the request thread — SC-002/SC-013). Status/resolve are
 * workspace-scoped; errors render through {@link CsvImportExceptionHandler} (value-free, no-oracle).
 */
@RestController
@RequestMapping("/api/internal/import")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class CsvImportController {

    private final CsvImportService service;

    public CsvImportController(CsvImportService service) {
        this.service = service;
    }

    @PostMapping("/csv")
    public ResponseEntity<CsvImportDtos.UploadAccepted> upload(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest http) {
        byte[] bytes;
        try {
            bytes = file == null ? null : file.getBytes();
        } catch (IOException e) {
            throw new CsvImportExceptions.InvalidImportException();
        }
        String filename = file == null ? null : file.getOriginalFilename();
        String contentType = file == null ? null : file.getContentType();
        CsvImportDtos.UploadAccepted accepted = service.accept(
            principal.workspaceId(), principal.memberId(), filename, bytes, contentType, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    @GetMapping("/{jobId}/status")
    public ResponseEntity<CsvImportDtos.JobStatusResponse> status(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("jobId") String jobId) {
        return ResponseEntity.ok(service.status(principal.workspaceId(), jobId));
    }

    @PostMapping("/{jobId}/resolve")
    public ResponseEntity<CsvImportDtos.JobStatusResponse> resolve(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("jobId") String jobId,
            @RequestBody CsvImportDtos.ResolveRequest req) {
        return ResponseEntity.ok(service.resolve(principal.workspaceId(), principal.memberId(), jobId, req));
    }
}
