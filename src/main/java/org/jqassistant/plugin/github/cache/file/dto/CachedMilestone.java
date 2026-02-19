package org.jqassistant.plugin.github.cache.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedMilestone {

    private int number;
    private String title;
    private String description;
    private String state;
    private String createdAt;
    private String updatedAt;
    private String dueOn;
    private String creatorLogin;
}
