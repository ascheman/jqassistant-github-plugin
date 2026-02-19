package org.jqassistant.plugin.github.scanner;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.plugin.common.api.scanner.filesystem.FileResource;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitBranchDescriptor;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitTagDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.jqassistant.plugin.github.cache.CacheEndpoint;
import org.jqassistant.plugin.github.cache.file.FileCacheStore;
import org.jqassistant.plugin.github.cache.file.GHObjectConverter;
import org.jqassistant.plugin.github.cache.file.dto.CachedBranch;
import org.jqassistant.plugin.github.cache.file.dto.CachedCommit;
import org.jqassistant.plugin.github.cache.file.dto.CachedRelease;
import org.jqassistant.plugin.github.cache.file.dto.CachedTag;
import org.jqassistant.plugin.github.cache.file.dto.CachedUser;
import org.jqassistant.plugin.github.cache.file.dto.ScanMetadata;
import org.jqassistant.plugin.github.model.GitHubIssue;
import org.jqassistant.plugin.github.model.GitHubMilestone;
import org.jqassistant.plugin.github.model.GitHubPullRequest;
import org.jqassistant.plugin.github.model.GitHubRelease;
import org.jqassistant.plugin.github.model.GitHubRepository;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTag;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * The GraphBuilder gets build only once per execution of the GitHub-Issues plugin
 * {@link GitHubIssueScannerPlugin#scan(FileResource, String, Scope, Scanner)} method.
 * <p>
 * It takes a list of specified repositories and starts to analyze them by following url paths.
 * <p>
 * The different tree depths are represented by the corresponding methods.
 */
@Slf4j
class GraphBuilder {

    private final CacheEndpoint cacheEndpoint;
    private final FileCacheStore fileCacheStore;

    GraphBuilder(CacheEndpoint cacheEndpoint, FileCacheStore fileCacheStore) {
        this.cacheEndpoint = cacheEndpoint;
        this.fileCacheStore = fileCacheStore;
    }

    GitHubRepository scanRepository(GHRepository ghRepository) throws IOException {

        log.info("Scanning repository '{}'", ghRepository.getFullName());

        logRateLimit("Rate limit for user: '{}'");

        GitHubRepository gitHubRepository = cacheEndpoint.findOrCreateGitHubRepository(ghRepository);

        ScanMetadata metadata = fileCacheStore != null ? fileCacheStore.readMetadata() : null;

        importBranches(ghRepository, gitHubRepository, metadata);
        importPullRequests(ghRepository, gitHubRepository);
        importMilestones(ghRepository, gitHubRepository);
        importIssues(ghRepository, gitHubRepository);
        importTags(ghRepository, gitHubRepository, metadata);
        importReleases(ghRepository, gitHubRepository);

        if (fileCacheStore != null && metadata != null) {
            metadata.setLastScanAt(FileCacheStore.nowTimestamp());
            fileCacheStore.writeMetadata(metadata);
        }

        log.info("Repository scan complete");
        logRateLimit("Remaining rate limit for user: {}");
        return gitHubRepository;
    }

    private void logRateLimit(String message) {
        try {
            GitHub gitHub = GitHub.connect();
            log.info(message, gitHub.getRateLimit());
        } catch (IOException e) {
            log.debug("Cannot retrieve rate limit (no credentials?): {}", e.getMessage());
        }
    }

    private void importBranches(GHRepository repository, GitHubRepository gitHubRepository, ScanMetadata metadata) {
        List<GitBranchDescriptor> branches = new LinkedList<>();
        try {
            repository.getBranches().forEach((String branchName, GHBranch ghBranch) -> {
                log.debug("Found remote branch: {}", branchName);

                if (fileCacheStore != null && metadata != null) {
                    String cachedHeadSha = metadata.getBranchHeads().get(branchName);
                    if (cachedHeadSha != null && cachedHeadSha.equals(ghBranch.getSHA1())) {
                        // HEAD unchanged — load from file cache
                        Optional<CachedBranch> cachedBranch = fileCacheStore.readBranch(branchName);
                        if (cachedBranch.isPresent()) {
                            List<CachedCommit> cachedCommits = loadCachedCommits(cachedBranch.get().getCommitShas());
                            if (cachedCommits.size() == cachedBranch.get().getCommitShas().size()) {
                                log.info("Branch '{}' HEAD unchanged ({}), loading {} commits from cache",
                                    branchName, cachedHeadSha.substring(0, 7), cachedCommits.size());
                                branches.add(cacheEndpoint.findOrCreateBranchFromCache(cachedBranch.get(), cachedCommits));
                                return;
                            }
                            log.debug("Branch '{}' cache incomplete, falling back to API", branchName);
                        }
                    }
                }

                // Fetch from API
                List<GHCommit> ghCommits;
                try {
                    ghCommits = repository.queryCommits().from(branchName).list().toList();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                // Write to file cache
                if (fileCacheStore != null && metadata != null) {
                    cacheCommitsAndUsers(ghCommits);
                    CachedBranch cachedBranch = GHObjectConverter.toBranch(ghBranch, ghCommits);
                    fileCacheStore.writeBranch(cachedBranch);
                    metadata.getBranchHeads().put(branchName, ghBranch.getSHA1());
                }

                branches.add(cacheEndpoint.findOrCreateGitHubBranch(ghBranch, ghCommits));
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        gitHubRepository.getBranches().addAll(branches);
        log.info("Imported {} branches", branches.size());
    }

    private void importPullRequests(GHRepository repository, GitHubRepository gitHubRepository) {
        List<GitHubPullRequest> pullRequests = new LinkedList<>();
        for (GHPullRequest ghPullRequest : repository.queryPullRequests().state(GHIssueState.ALL).list()) {
            log.debug("Found pull request: {}", ghPullRequest.getNumber());
            List<GHCommit> ghCommits = new LinkedList<>();
            // Retrieve all commits that belong to this PR
            ghPullRequest.listCommits().forEach(commitDetail -> {
                try {
                    String sha = commitDetail.getSha();
                    // Check file cache first for the commit
                    if (fileCacheStore != null && fileCacheStore.hasCommit(sha)) {
                        log.debug("PR #{} commit '{}' already cached, loading from file cache", ghPullRequest.getNumber(), sha.substring(0, 7));
                    }
                    // Still need the GHCommit for the existing PR flow
                    ghCommits.add(repository.getCommit(sha));
                } catch (IOException e) {
                    log.warn("Cannot retrieve remote commit '{}' for PullRequest #{}", commitDetail.getSha(), ghPullRequest.getNumber());
                }
            });

            // Cache commits and users from PR
            if (fileCacheStore != null) {
                cacheCommitsAndUsers(ghCommits);
            }

            pullRequests.add(cacheEndpoint.findOrCreatePullRequest(ghPullRequest, ghCommits));
        }
        gitHubRepository.getPullRequests().addAll(pullRequests);
        log.info("Imported {} pull requests", pullRequests.size());
    }

    private void importMilestones(GHRepository repository, GitHubRepository gitHubRepository) {
        List<GitHubMilestone> milestones = new LinkedList<>();
        for (GHMilestone milestone : repository.listMilestones(GHIssueState.ALL)) {
            log.debug("Found milestone: {}", milestone.getNumber());
            milestones.add(cacheEndpoint.findOrCreateGitHubMilestone(milestone));
        }
        gitHubRepository.getMilestones().addAll(milestones);
        log.info("Imported {} milestones", milestones.size());
    }

    private void importReleases(GHRepository repository, GitHubRepository gitHubRepository) {
        List<GitHubRelease> releases = new LinkedList<>();
        try {
            for (GHRelease release : repository.listReleases()) {
                log.debug("Found release: {}", release.getName());

                // Cache the release
                if (fileCacheStore != null) {
                    CachedRelease cachedRelease = GHObjectConverter.toRelease(release);
                    fileCacheStore.writeRelease(cachedRelease);
                }

                releases.add(cacheEndpoint.findOrCreateGitHubRelease(release));
            }
            gitHubRepository.getReleases().addAll(releases);
            log.info("Imported {} releases", releases.size());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void importTags(GHRepository repository, GitHubRepository gitHubRepository, ScanMetadata metadata) {
        List<GitTagDescriptor> tags = new LinkedList<>();
        try {
            for (GHTag tag : repository.listTags()) {
                log.debug("Found tag: {}", tag.getName());

                // Tags are immutable — if we have it cached, use it
                if (fileCacheStore != null && metadata != null) {
                    String cachedTagCommit = metadata.getTagCommits().get(tag.getName());
                    if (cachedTagCommit != null) {
                        Optional<CachedTag> cachedTag = fileCacheStore.readTag(tag.getName());
                        if (cachedTag.isPresent()) {
                            List<CachedCommit> cachedCommits = loadCachedCommits(cachedTag.get().getCommitShas());
                            if (cachedCommits.size() == cachedTag.get().getCommitShas().size()) {
                                log.info("Tag '{}' found in cache, loading {} commits from cache",
                                    tag.getName(), cachedCommits.size());
                                tags.add(cacheEndpoint.findOrCreateTagFromCache(cachedTag.get(), cachedCommits));
                                continue;
                            }
                        }
                    }
                }

                // Fetch from API
                List<GHCommit> ghCommits = new LinkedList<>();
                try {
                    var ref = repository.getRef("tags/" + tag.getName());
                    if (ref != null && ref.getObject() != null && ref.getObject().getSha() != null) {
                        String sha = ref.getObject().getSha();
                        ghCommits = repository.queryCommits().from(sha).list().toList();
                    } else {
                        log.warn("Cannot resolve ref for tag '{}'", tag.getName());
                    }
                } catch (IOException e) {
                    log.warn("Cannot retrieve commits for tag '{}' from '{}'", tag.getName(), repository.getHtmlUrl(), e);
                }

                if (!ghCommits.isEmpty()) {
                    // Write to file cache
                    if (fileCacheStore != null && metadata != null) {
                        cacheCommitsAndUsers(ghCommits);
                        CachedTag cachedTag = GHObjectConverter.toTag(tag, ghCommits);
                        fileCacheStore.writeTag(cachedTag);
                        metadata.getTagCommits().put(tag.getName(), tag.getCommit().getSHA1());
                    }
                    tags.add(cacheEndpoint.findOrCreateGitHubTag(tag, ghCommits));
                } else {
                    log.warn("No commits resolved for tag '{}', skipping tag import.", tag.getName());
                }
            }
            gitHubRepository.getTags().addAll(tags);
            log.info("Imported {} tags", tags.size());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void importIssues(GHRepository repository, GitHubRepository gitHubRepository) {
        List<GitHubIssue> issues = new LinkedList<>();
        for (GHIssue issue : repository.queryIssues().state(GHIssueState.ALL).list()) {
            log.debug("Found issue: {}", issue.getNumber());
            issues.add(cacheEndpoint.findOrCreateGitHubIssue(issue));
            if (issue.isPullRequest()) {
                log.debug("Issue {} already imported as Pull Request", issue.getNumber());
            }
        }
        gitHubRepository.getIssues().addAll(issues);
        log.info("Imported {} issues", issues.size());
    }

    // --- File cache helpers ---

    /**
     * Cache all commits and their associated users to the file cache.
     */
    private void cacheCommitsAndUsers(List<GHCommit> ghCommits) {
        for (GHCommit ghCommit : ghCommits) {
            if (!fileCacheStore.hasCommit(ghCommit.getSHA1())) {
                CachedCommit cachedCommit = GHObjectConverter.toCommit(ghCommit);
                fileCacheStore.writeCommit(cachedCommit);
                cacheUserFromCommit(ghCommit);
            }
        }
    }

    /**
     * Cache the author and committer users from a commit.
     */
    private void cacheUserFromCommit(GHCommit ghCommit) {
        try {
            if (ghCommit.getAuthor() != null && ghCommit.getAuthor().getLogin() != null) {
                String login = ghCommit.getAuthor().getLogin();
                if (fileCacheStore.readUser(login).isEmpty()) {
                    fileCacheStore.writeUser(GHObjectConverter.toUser(ghCommit.getAuthor()));
                }
            }
        } catch (IOException e) {
            log.debug("Cannot cache author for commit '{}': {}", ghCommit.getSHA1(), e.getMessage());
        }
        try {
            if (ghCommit.getCommitter() != null && ghCommit.getCommitter().getLogin() != null) {
                String login = ghCommit.getCommitter().getLogin();
                if (fileCacheStore.readUser(login).isEmpty()) {
                    fileCacheStore.writeUser(GHObjectConverter.toUser(ghCommit.getCommitter()));
                }
            }
        } catch (IOException e) {
            log.debug("Cannot cache committer for commit '{}': {}", ghCommit.getSHA1(), e.getMessage());
        }
    }

    /**
     * Load a list of cached commits by their SHAs.
     * Returns a list that may be shorter than the input if some commits are missing from cache.
     */
    private List<CachedCommit> loadCachedCommits(List<String> shas) {
        List<CachedCommit> result = new ArrayList<>(shas.size());
        for (String sha : shas) {
            Optional<CachedCommit> cached = fileCacheStore.readCommit(sha);
            if (cached.isPresent()) {
                result.add(cached.get());
            } else {
                log.debug("Commit '{}' not found in file cache", sha);
                break;
            }
        }
        return result;
    }
}
