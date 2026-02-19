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
public class CachedIssue {

    private int number;
    private String title;
    private String body;
    private String state;
    private boolean locked;
    private String createdAt;
    private String updatedAt;
    private String closedAt;
    private String createdByLogin;
    private String closedByLogin;
    private List<String> assigneeLogins;
    private List<String> labelNames;
    private Integer milestoneNumber;
    private boolean pullRequest;
}
