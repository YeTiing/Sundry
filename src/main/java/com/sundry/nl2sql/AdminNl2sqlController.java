package com.sundry.nl2sql;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.api.PageResponse;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.nl2sql.dto.Nl2sqlLogView;
import com.sundry.nl2sql.dto.Nl2sqlQueryRequest;
import com.sundry.nl2sql.dto.Nl2sqlQueryView;
import com.sundry.nl2sql.dto.SemanticVocabulary;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/nl2sql")
public class AdminNl2sqlController {

    private final SemanticLayerProvider provider;
    private final Nl2sqlService nl2sqlService;

    public AdminNl2sqlController(SemanticLayerProvider provider, Nl2sqlService nl2sqlService) {
        this.provider = provider;
        this.nl2sqlService = nl2sqlService;
    }

    @GetMapping("/vocabulary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public SemanticVocabulary vocabulary() {
        return provider.layer().vocabulary();
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public PageResponse<Nl2sqlLogView> logs(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String status) {
        return nl2sqlService.logs(page, size, status);
    }

    @PostMapping("/query")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public Nl2sqlQueryView query(@Valid @RequestBody Nl2sqlQueryRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return nl2sqlService.query(user, request);
    }
}
