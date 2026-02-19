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
}
