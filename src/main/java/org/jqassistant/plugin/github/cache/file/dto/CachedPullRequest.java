package org.jqassistant.plugin.github.cache.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class CachedPullRequest extends CachedIssue {

    private String mergedAt;
    private String baseSha;
    private String headSha;
    private List<String> commitShas;

    @Builder(builderMethodName = "prBuilder")
    public CachedPullRequest(int number, String title, String body, String state, boolean locked,
                             String createdAt, String updatedAt, String closedAt,
                             String createdByLogin, String closedByLogin,
                             List<String> assigneeLogins, List<String> labelNames,
                             Integer milestoneNumber, boolean pullRequest,
                             String mergedAt, String baseSha, String headSha,
                             List<String> commitShas) {
        super(number, title, body, state, locked, createdAt, updatedAt, closedAt,
              createdByLogin, closedByLogin, assigneeLogins, labelNames, milestoneNumber, pullRequest);
        this.mergedAt = mergedAt;
        this.baseSha = baseSha;
        this.headSha = headSha;
        this.commitShas = commitShas;
    }
}
