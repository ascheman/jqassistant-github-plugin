package org.jqassistant.plugin.github.cache.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedTag {

    private String name;
    private String commitSha;
    private List<String> commitShas;
}
