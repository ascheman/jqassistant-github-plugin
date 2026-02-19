package org.jqassistant.plugin.github.cache.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedRelease {

    private String name;
    private String body;
    private String tagName;
}
