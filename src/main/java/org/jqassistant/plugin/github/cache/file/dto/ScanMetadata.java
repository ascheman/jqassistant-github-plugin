package org.jqassistant.plugin.github.cache.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanMetadata {

    private String lastScanAt;

    @Builder.Default
    private Map<String, String> branchHeads = new HashMap<>();

    @Builder.Default
    private Map<String, String> tagCommits = new HashMap<>();
}
