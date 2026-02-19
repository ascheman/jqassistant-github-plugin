package org.jqassistant.plugin.github.cache.file;

import lombok.extern.slf4j.Slf4j;
import org.jqassistant.plugin.github.cache.file.dto.*;
import org.kohsuke.github.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts kohsuke {@code GH*} objects to cache DTOs.
 * All methods handle IOExceptions gracefully with logging.
 */
@Slf4j
public final class GHObjectConverter {

    private GHObjectConverter() {
    }

    public static CachedCommit toCommit(GHCommit ghCommit) {
        CachedCommit.CachedCommitBuilder builder = CachedCommit.builder()
            .sha(ghCommit.getSHA1());

        try {
            builder.date(ghCommit.getCommitDate().toString());
        } catch (IOException e) {
            log.warn("Cannot get commit date for '{}': {}", ghCommit.getSHA1(), e.getMessage());
        }

        try {
            GHUser author = ghCommit.getAuthor();
            if (author != null) {
                builder.authorLogin(author.getLogin());
                try { builder.authorName(author.getName()); } catch (IOException e) { /* ignore */ }
                try { builder.authorEmail(author.getEmail()); } catch (IOException e) { /* ignore */ }
            }
        } catch (IOException e) {
            log.debug("Cannot resolve GitHub author for '{}', trying git info", ghCommit.getSHA1());
            try {
                GitUser gitAuthor = ghCommit.getCommitShortInfo().getAuthor();
                if (gitAuthor != null) {
                    builder.authorName(gitAuthor.getName());
                    builder.authorEmail(gitAuthor.getEmail());
                }
            } catch (IOException e2) {
                log.warn("Cannot resolve git author for '{}': {}", ghCommit.getSHA1(), e2.getMessage());
            }
        }

        try {
            GHUser committer = ghCommit.getCommitter();
            if (committer != null) {
                builder.committerLogin(committer.getLogin());
                try { builder.committerName(committer.getName()); } catch (IOException e) { /* ignore */ }
                try { builder.committerEmail(committer.getEmail()); } catch (IOException e) { /* ignore */ }
            }
        } catch (IOException e) {
            log.debug("Cannot resolve GitHub committer for '{}', trying git info", ghCommit.getSHA1());
            try {
                GitUser gitCommitter = ghCommit.getCommitShortInfo().getCommitter();
                if (gitCommitter != null) {
                    builder.committerName(gitCommitter.getName());
                    builder.committerEmail(gitCommitter.getEmail());
                }
            } catch (IOException e2) {
                log.warn("Cannot resolve git committer for '{}': {}", ghCommit.getSHA1(), e2.getMessage());
            }
        }

        try {
            List<String> parentShas = new ArrayList<>();
            for (GHCommit parent : ghCommit.getParents()) {
                parentShas.add(parent.getSHA1());
            }
            builder.parentShas(parentShas);
        } catch (IOException e) {
            log.warn("Cannot get parents for '{}': {}", ghCommit.getSHA1(), e.getMessage());
            builder.parentShas(List.of());
        }

        return builder.build();
    }

    public static CachedUser toUser(GHUser ghUser) {
        CachedUser.CachedUserBuilder builder = CachedUser.builder()
            .cachedAt(FileCacheStore.nowTimestamp());

        try {
            builder.login(ghUser.getLogin());
        } catch (Exception e) {
            log.warn("Cannot get login for user: {}", e.getMessage());
        }

        try {
            builder.name(ghUser.getName());
        } catch (IOException e) {
            log.debug("Cannot get name for user '{}': {}", ghUser.getLogin(), e.getMessage());
        }

        try {
            builder.email(ghUser.getEmail());
        } catch (IOException e) {
            log.debug("Cannot get email for user '{}': {}", ghUser.getLogin(), e.getMessage());
        }

        return builder.build();
    }

    public static CachedBranch toBranch(GHBranch ghBranch, List<GHCommit> ghCommits) {
        List<String> shas = new ArrayList<>(ghCommits.size());
        for (GHCommit c : ghCommits) {
            shas.add(c.getSHA1());
        }
        return CachedBranch.builder()
            .name(ghBranch.getName())
            .headSha(ghBranch.getSHA1())
            .commitShas(shas)
            .build();
    }

    public static CachedTag toTag(GHTag ghTag, List<GHCommit> ghCommits) {
        List<String> shas = new ArrayList<>(ghCommits.size());
        for (GHCommit c : ghCommits) {
            shas.add(c.getSHA1());
        }
        return CachedTag.builder()
            .name(ghTag.getName())
            .commitSha(ghTag.getCommit().getSHA1())
            .commitShas(shas)
            .build();
    }

    public static CachedRelease toRelease(GHRelease ghRelease) {
        return CachedRelease.builder()
            .name(ghRelease.getName())
            .body(ghRelease.getBody())
            .tagName(ghRelease.getTagName())
            .build();
    }

    public static CachedLabel toLabel(GHLabel ghLabel) {
        return CachedLabel.builder()
            .name(ghLabel.getName())
            .description(ghLabel.getDescription())
            .color(ghLabel.getColor())
            .build();
    }

    public static CachedIssue toIssue(GHIssue ghIssue) {
        CachedIssue.CachedIssueBuilder builder = CachedIssue.builder()
            .number(ghIssue.getNumber())
            .title(ghIssue.getTitle())
            .body(ghIssue.getBody())
            .state(ghIssue.getState().name())
            .locked(ghIssue.isLocked())
            .pullRequest(ghIssue.isPullRequest());

        try {
            if (ghIssue.getCreatedAt() != null) {
                builder.createdAt(ghIssue.getCreatedAt().toInstant().toString());
            }
        } catch (IOException e) {
            log.debug("Cannot get createdAt for issue #{}: {}", ghIssue.getNumber(), e.getMessage());
        }
        try {
            if (ghIssue.getUpdatedAt() != null) {
                builder.updatedAt(ghIssue.getUpdatedAt().toInstant().toString());
            }
        } catch (IOException e) {
            log.debug("Cannot get updatedAt for issue #{}: {}", ghIssue.getNumber(), e.getMessage());
        }
        if (ghIssue.getClosedAt() != null) {
            builder.closedAt(ghIssue.getClosedAt().toInstant().toString());
        }

        try {
            if (ghIssue.getUser() != null && ghIssue.getUser().getLogin() != null) {
                builder.createdByLogin(ghIssue.getUser().getLogin());
            }
        } catch (IOException e) {
            log.debug("Cannot get creator for issue #{}: {}", ghIssue.getNumber(), e.getMessage());
        }
        try {
            if (ghIssue.getClosedBy() != null && ghIssue.getClosedBy().getLogin() != null) {
                builder.closedByLogin(ghIssue.getClosedBy().getLogin());
            }
        } catch (IOException e) {
            log.debug("Cannot get closedBy for issue #{}: {}", ghIssue.getNumber(), e.getMessage());
        }

        List<String> assigneeLogins = new ArrayList<>();
        for (GHUser assignee : ghIssue.getAssignees()) {
            if (assignee.getLogin() != null) {
                assigneeLogins.add(assignee.getLogin());
            }
        }
        builder.assigneeLogins(assigneeLogins);

        List<String> labelNames = new ArrayList<>();
        for (GHLabel label : ghIssue.getLabels()) {
            labelNames.add(label.getName());
        }
        builder.labelNames(labelNames);

        if (ghIssue.getMilestone() != null) {
            builder.milestoneNumber(ghIssue.getMilestone().getNumber());
        }

        return builder.build();
    }

    public static CachedPullRequest toPullRequest(GHPullRequest ghPr, List<GHCommit> ghCommits) {
        CachedPullRequest pr = new CachedPullRequest();
        // Populate base issue fields
        pr.setNumber(ghPr.getNumber());
        pr.setTitle(ghPr.getTitle());
        pr.setBody(ghPr.getBody());
        pr.setState(ghPr.getState().name());
        pr.setLocked(ghPr.isLocked());
        pr.setPullRequest(true);

        try {
            if (ghPr.getCreatedAt() != null) {
                pr.setCreatedAt(ghPr.getCreatedAt().toInstant().toString());
            }
        } catch (IOException e) {
            log.debug("Cannot get createdAt for PR #{}: {}", ghPr.getNumber(), e.getMessage());
        }
        try {
            if (ghPr.getUpdatedAt() != null) {
                pr.setUpdatedAt(ghPr.getUpdatedAt().toInstant().toString());
            }
        } catch (IOException e) {
            log.debug("Cannot get updatedAt for PR #{}: {}", ghPr.getNumber(), e.getMessage());
        }
        if (ghPr.getClosedAt() != null) {
            pr.setClosedAt(ghPr.getClosedAt().toInstant().toString());
        }
        if (ghPr.getMergedAt() != null) {
            pr.setMergedAt(ghPr.getMergedAt().toInstant().toString());
        }

        try {
            if (ghPr.getUser() != null) {
                pr.setCreatedByLogin(ghPr.getUser().getLogin());
            }
        } catch (IOException e) { /* ignore */ }
        if (ghPr.getClosedBy() != null) {
            pr.setClosedByLogin(ghPr.getClosedBy().getLogin());
        }

        List<String> assigneeLogins = new ArrayList<>();
        for (GHUser a : ghPr.getAssignees()) {
            if (a.getLogin() != null) assigneeLogins.add(a.getLogin());
        }
        pr.setAssigneeLogins(assigneeLogins);

        List<String> labelNames = new ArrayList<>();
        for (GHLabel l : ghPr.getLabels()) {
            labelNames.add(l.getName());
        }
        pr.setLabelNames(labelNames);

        if (ghPr.getMilestone() != null) {
            pr.setMilestoneNumber(ghPr.getMilestone().getNumber());
        }

        // PR-specific fields
        if (ghPr.getBase() != null) {
            pr.setBaseSha(ghPr.getBase().getSha());
        }
        if (ghPr.getHead() != null) {
            pr.setHeadSha(ghPr.getHead().getSha());
        }

        List<String> commitShas = new ArrayList<>(ghCommits.size());
        for (GHCommit c : ghCommits) {
            commitShas.add(c.getSHA1());
        }
        pr.setCommitShas(commitShas);

        return pr;
    }

    public static CachedMilestone toMilestone(GHMilestone ghMilestone) {
        CachedMilestone.CachedMilestoneBuilder builder = CachedMilestone.builder()
            .number(ghMilestone.getNumber())
            .title(ghMilestone.getTitle())
            .description(ghMilestone.getDescription())
            .state(ghMilestone.getState().name());

        try {
            if (ghMilestone.getCreatedAt() != null) {
                builder.createdAt(ghMilestone.getCreatedAt().toInstant().toString());
            }
        } catch (IOException e) {
            log.debug("Cannot get createdAt for milestone #{}: {}", ghMilestone.getNumber(), e.getMessage());
        }
        try {
            if (ghMilestone.getUpdatedAt() != null) {
                builder.updatedAt(ghMilestone.getUpdatedAt().toInstant().toString());
            }
        } catch (IOException e) {
            log.debug("Cannot get updatedAt for milestone #{}: {}", ghMilestone.getNumber(), e.getMessage());
        }
        if (ghMilestone.getDueOn() != null) {
            builder.dueOn(ghMilestone.getDueOn().toInstant().toString());
        }
        try {
            if (ghMilestone.getCreator() != null) {
                builder.creatorLogin(ghMilestone.getCreator().getLogin());
            }
        } catch (IOException e) {
            log.debug("Cannot get creator for milestone #{}: {}", ghMilestone.getNumber(), e.getMessage());
        }

        return builder.build();
    }
}
