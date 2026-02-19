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
import org.jqassistant.plugin.github.cache.file.dto.CachedIssue;
import org.jqassistant.plugin.github.cache.file.dto.CachedMilestone;
import org.jqassistant.plugin.github.cache.file.dto.CachedPullRequest;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
        importMilestones(ghRepository, gitHubRepository);
        importPullRequests(ghRepository, gitHubRepository, metadata);
        importIssues(ghRepository, gitHubRepository, metadata);
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

    private void importPullRequests(GHRepository repository, GitHubRepository gitHubRepository, ScanMetadata metadata) {
        List<GitHubPullRequest> pullRequests = new LinkedList<>();

        if (fileCacheStore != null) {
            // Load all cached PRs, keyed by number
            Map<Integer, CachedPullRequest> cachedPrMap = new HashMap<>();
            for (CachedPullRequest cached : fileCacheStore.readAllPullRequests()) {
                cachedPrMap.put(cached.getNumber(), cached);
            }

            int fromCache = 0;
            int fromApi = 0;

            for (GHPullRequest ghPr : repository.queryPullRequests().state(GHIssueState.ALL).list()) {
                int number = ghPr.getNumber();
                log.debug("Found pull request: {}", number);

                CachedPullRequest cachedPr = cachedPrMap.remove(number);

                // Closed/merged PRs are immutable — use cache if available
                if (cachedPr != null && !"OPEN".equalsIgnoreCase(ghPr.getState().name())) {
                    log.debug("PR #{} is closed/merged, loading from cache", number);
                    pullRequests.add(cacheEndpoint.findOrCreatePullRequestFromCache(cachedPr));
                    fromCache++;
                    continue;
                }

                // Open PR or not cached: fetch from API
                List<GHCommit> ghCommits = new LinkedList<>();
                ghPr.listCommits().forEach(commitDetail -> {
                    try {
                        String sha = commitDetail.getSha();
                        ghCommits.add(repository.getCommit(sha));
                    } catch (IOException e) {
                        log.warn("Cannot retrieve remote commit '{}' for PullRequest #{}", commitDetail.getSha(), number);
                    }
                });

                cacheCommitsAndUsers(ghCommits);

                // Cache the PR
                CachedPullRequest newCachedPr = GHObjectConverter.toPullRequest(ghPr, ghCommits);
                fileCacheStore.writePullRequest(newCachedPr);

                pullRequests.add(cacheEndpoint.findOrCreatePullRequest(ghPr, ghCommits));
                fromApi++;
            }

            // Load remaining cached PRs that weren't returned by the API listing
            // (this shouldn't normally happen, but handles edge cases)
            for (CachedPullRequest remaining : cachedPrMap.values()) {
                log.debug("PR #{} only in cache (not returned by API), loading from cache", remaining.getNumber());
                pullRequests.add(cacheEndpoint.findOrCreatePullRequestFromCache(remaining));
                fromCache++;
            }

            log.info("Imported {} pull requests ({} from cache, {} from API)", pullRequests.size(), fromCache, fromApi);
        } else {
            // No cache — original behavior
            for (GHPullRequest ghPullRequest : repository.queryPullRequests().state(GHIssueState.ALL).list()) {
                log.debug("Found pull request: {}", ghPullRequest.getNumber());
                List<GHCommit> ghCommits = new LinkedList<>();
                ghPullRequest.listCommits().forEach(commitDetail -> {
                    try {
                        ghCommits.add(repository.getCommit(commitDetail.getSha()));
                    } catch (IOException e) {
                        log.warn("Cannot retrieve remote commit '{}' for PullRequest #{}", commitDetail.getSha(), ghPullRequest.getNumber());
                    }
                });
                pullRequests.add(cacheEndpoint.findOrCreatePullRequest(ghPullRequest, ghCommits));
            }
            log.info("Imported {} pull requests", pullRequests.size());
        }
        gitHubRepository.getPullRequests().addAll(pullRequests);
    }

    private void importMilestones(GHRepository repository, GitHubRepository gitHubRepository) {
        List<GitHubMilestone> milestones = new LinkedList<>();

        if (fileCacheStore != null) {
            // Load all cached milestones
            Map<Integer, CachedMilestone> cachedMilestoneMap = new HashMap<>();
            for (CachedMilestone cached : fileCacheStore.readAllMilestones()) {
                cachedMilestoneMap.put(cached.getNumber(), cached);
            }

            int fromCache = 0;
            int fromApi = 0;

            for (GHMilestone ghMilestone : repository.listMilestones(GHIssueState.ALL)) {
                int number = ghMilestone.getNumber();
                log.debug("Found milestone: {}", number);

                CachedMilestone cachedMilestone = cachedMilestoneMap.remove(number);

                // Closed milestones are immutable — use cache if available
                if (cachedMilestone != null && "CLOSED".equalsIgnoreCase(ghMilestone.getState().name())) {
                    log.debug("Milestone #{} is closed, loading from cache", number);
                    milestones.add(cacheEndpoint.findOrCreateMilestoneFromCache(cachedMilestone));
                    fromCache++;
                    continue;
                }

                // Open or not cached: fetch from API and cache
                CachedMilestone newCachedMilestone = GHObjectConverter.toMilestone(ghMilestone);
                fileCacheStore.writeMilestone(newCachedMilestone);

                milestones.add(cacheEndpoint.findOrCreateGitHubMilestone(ghMilestone));
                fromApi++;
            }

            log.info("Imported {} milestones ({} from cache, {} from API)", milestones.size(), fromCache, fromApi);
        } else {
            for (GHMilestone milestone : repository.listMilestones(GHIssueState.ALL)) {
                log.debug("Found milestone: {}", milestone.getNumber());
                milestones.add(cacheEndpoint.findOrCreateGitHubMilestone(milestone));
            }
            log.info("Imported {} milestones", milestones.size());
        }
        gitHubRepository.getMilestones().addAll(milestones);
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

    private void importIssues(GHRepository repository, GitHubRepository gitHubRepository, ScanMetadata metadata) {
        List<GitHubIssue> issues = new LinkedList<>();

        if (fileCacheStore != null && metadata != null) {
            // Load all cached issues, keyed by number
            Map<Integer, CachedIssue> cachedIssueMap = new HashMap<>();
            for (CachedIssue cached : fileCacheStore.readAllIssues()) {
                cachedIssueMap.put(cached.getNumber(), cached);
            }

            // Fetch only issues updated since last scan (or all if no previous scan)
            var queryBuilder = repository.queryIssues().state(GHIssueState.ALL);
            if (metadata.getLastScanAt() != null) {
                ZonedDateTime lastScan = ZonedDateTime.parse(metadata.getLastScanAt());
                queryBuilder.since(java.util.Date.from(lastScan.toInstant()));
                log.info("Fetching issues updated since {}", metadata.getLastScanAt());
            }

            int fromCache = 0;
            int fromApi = 0;

            // Process updated issues from API
            for (GHIssue ghIssue : queryBuilder.list()) {
                int number = ghIssue.getNumber();
                log.debug("Found updated issue: {}", number);
                cachedIssueMap.remove(number);

                // Cache the issue
                CachedIssue cachedIssue = GHObjectConverter.toIssue(ghIssue);
                fileCacheStore.writeIssue(cachedIssue);

                issues.add(cacheEndpoint.findOrCreateGitHubIssue(ghIssue));
                if (ghIssue.isPullRequest()) {
                    log.debug("Issue {} already imported as Pull Request", number);
                }
                fromApi++;
            }

            // Load remaining cached issues that were not updated
            for (CachedIssue cached : cachedIssueMap.values()) {
                log.debug("Issue #{} unchanged, loading from cache", cached.getNumber());
                issues.add(cacheEndpoint.findOrCreateIssueFromCache(cached));
                fromCache++;
            }

            log.info("Imported {} issues ({} from cache, {} from API)", issues.size(), fromCache, fromApi);
        } else {
            // No cache — original behavior
            for (GHIssue issue : repository.queryIssues().state(GHIssueState.ALL).list()) {
                log.debug("Found issue: {}", issue.getNumber());
                issues.add(cacheEndpoint.findOrCreateGitHubIssue(issue));
                if (issue.isPullRequest()) {
                    log.debug("Issue {} already imported as Pull Request", issue.getNumber());
                }
            }
            log.info("Imported {} issues", issues.size());
        }
        gitHubRepository.getIssues().addAll(issues);
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
