package com.sundry.nl2sql;

import com.sundry.nl2sql.dto.SemanticVocabulary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/nl2sql")
public class AdminNl2sqlController {

    private final SemanticLayerProvider provider;

    public AdminNl2sqlController(SemanticLayerProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/vocabulary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public SemanticVocabulary vocabulary() {
        return provider.layer().vocabulary();
    }
}
