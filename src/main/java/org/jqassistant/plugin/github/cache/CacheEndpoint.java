package org.jqassistant.plugin.github.cache;

import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.xo.api.Query;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitBranchDescriptor;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitCommitDescriptor;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitTagDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.jqassistant.plugin.github.model.GitHubIssue;
import org.jqassistant.plugin.github.model.GitHubLabel;
import org.jqassistant.plugin.github.model.GitHubMilestone;
import org.jqassistant.plugin.github.model.GitHubPullRequest;
import org.jqassistant.plugin.github.model.GitHubRelease;
import org.jqassistant.plugin.github.model.GitHubRepository;
import org.jqassistant.plugin.github.model.GitHubUser;
import org.jqassistant.plugin.github.cache.file.dto.CachedBranch;
import org.jqassistant.plugin.github.cache.file.dto.CachedCommit;
import org.jqassistant.plugin.github.cache.file.dto.CachedIssue;
import org.jqassistant.plugin.github.cache.file.dto.CachedMilestone;
import org.jqassistant.plugin.github.cache.file.dto.CachedPullRequest;
import org.jqassistant.plugin.github.cache.file.dto.CachedRelease;
import org.jqassistant.plugin.github.cache.file.dto.CachedTag;
import org.jqassistant.plugin.github.cache.file.dto.CachedUser;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTag;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitUser;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static de.kontext_e.jqassistant.plugin.git.scanner.repositories.JQAssistantGitRepository.getCommitDescriptorFromDB;

/**
 * <p>
 * This class was written to handle nodes in the resulting graph that get referenced more than one
 * time. These nodes get cached in the {@link DescriptorCache}.
 * </p>
 * <p>
 * All of the methods in this class work similar:
 * </p>
 * They check if a certain descriptor instance exists. If it does
 * exist, they return the instance. Otherwise they create a new one and save it in the
 * {@link Store} and in the {@link DescriptorCache}.
 */
@Slf4j
public class CacheEndpoint {

    private final Store store;
    private final DescriptorCache descriptorCache;

    public CacheEndpoint(Store store) {

        this.store = store;
        descriptorCache = new DescriptorCache();
    }

    /**
     * Check for {@link GitHubRepository}.
     *
     * @param ghRepository The GitHub repository.
     * @return The retrieved or newly created descriptor instance.
     */
    public GitHubRepository findOrCreateGitHubRepository(GHRepository ghRepository) {

        log.debug("Creating new repository {} for owner {}: ", ghRepository.getName(), ghRepository.getOwnerName());
        GitHubRepository repository = store.create(GitHubRepository.class);
        repository.setOwner(ghRepository.getOwnerName());
        repository.setName(ghRepository.getName());
        descriptorCache.setRepository(repository);

        return repository;
    }

    public GitHubPullRequest findOrCreatePullRequest(GHPullRequest ghPullRequest, List<GHCommit> ghCommits) {
        // A pull request is a special kind of issue
        return this.descriptorCache.getPullRequest(ghPullRequest.getNumber())
            .orElseGet(() -> createGitHubPullRequest(ghPullRequest, ghCommits));
    }

    private GitHubPullRequest createGitHubPullRequest(GHPullRequest ghPullRequest, List<GHCommit> ghCommits) {
        log.debug("Creating new pull request '{}' with {} commits", ghPullRequest.getHtmlUrl(), ghCommits.size());
        addGitCommits(ghCommits, false);

        GitHubPullRequest pullRequest = store.create(GitHubPullRequest.class);
        if (ghPullRequest.getMergedAt() != null) {
            pullRequest.setMergedAt(ghPullRequest.getMergedAt().toInstant().atZone(ZoneOffset.UTC));
        }
        try {
            log.debug("Adding '{}' as base to '{}'", ghPullRequest.getBase().getSha(), ghPullRequest.getHtmlUrl());
            findExistingGitCommit(ghPullRequest.getBase().getCommit()).ifPresent(pullRequest::setBase);
        } catch (IOException e) {
            log.warn("Cannot retrieve Base Commit '{}' for '{}'",
                ghPullRequest.getBase().getRef(),
                ghPullRequest.getHtmlUrl()
            );
        }
        var head = ghPullRequest.getHead();
        if (null == head) {
            log.warn("Cannot find Head Commit for '{}#{}'",
                ghPullRequest.getHtmlUrl(),
                ghPullRequest.getNumber()
            );
        } else {
            if (null == head.getRepository()) {
                log.warn("Cannot retrieve Head Repository with Commit '{}' of '{}' ({})",
                    head.getSha(),
                    ghPullRequest.getHtmlUrl(),
                    head.getRef()
                );
            } else {
                try {
                    log.debug("Adding '{}' as head to '{}'", head.getSha(), ghPullRequest.getHtmlUrl());
                    findExistingGitCommit(head.getCommit()).ifPresent(pullRequest::setHead);
                } catch (IOException e) {
                    log.warn("Cannot retrieve Head Commit '{}' for '{}' ({})",
                        head.getSha(),
                        ghPullRequest.getHtmlUrl(),
                        head.getRef()

                    );
                }
            }
        }

        populateIssueInformation(pullRequest, ghPullRequest);
        descriptorCache.put(pullRequest);

        return pullRequest;
    }

    /**
     * Check for {@link GitHubIssue}.
     *
     * @param ghIssue The GitHub issue information.
     * @return The retrieved or newly created descriptor instance.
     */
    public GitHubIssue findOrCreateGitHubIssue(GHIssue ghIssue) {
        Optional<GitHubIssue> optionalIssue = this.descriptorCache.getIssue(ghIssue.getNumber());

        return optionalIssue.orElseGet(() -> createGitHubIssue(ghIssue));
    }

    private GitHubIssue createGitHubIssue(GHIssue ghIssue) {
        log.debug("Creating new issue: {}", ghIssue);
        GitHubIssue issue;
        issue = store.create(GitHubIssue.class);
        populateIssueInformation(issue, ghIssue);
        descriptorCache.put(issue);

        return issue;
    }

    private void populateIssueInformation(GitHubIssue issue, GHIssue ghIssue) {
        issue.setNumber(ghIssue.getNumber());
        issue.setLocked(ghIssue.isLocked());
        issue.setState(ghIssue.getState().name());
        issue.setTitle(ghIssue.getTitle());
        issue.setBody(ghIssue.getBody());
        try {
            issue.setCreatedAt(ghIssue.getCreatedAt().toInstant().atZone(ZoneOffset.UTC));
        } catch (IOException e) {
            // todo
        }
        try {
            findOrCreateGitHubUser(ghIssue.getUser()).ifPresent(issue::setCreatedBy);
        } catch (IOException e) {
            log.warn("Cannot resolve creator for issue #{}: {}", ghIssue.getNumber(), e.getMessage());
        }
        try {
            issue.setUpdatedAt(ghIssue.getUpdatedAt().toInstant().atZone(ZoneOffset.UTC));
        } catch (IOException e) {
            // todo
        }
        if (ghIssue.getMilestone() != null) {
            issue.setMilestone(findOrCreateGitHubMilestone(ghIssue.getMilestone()));
        }
        if (ghIssue.getClosedAt() != null) {
            issue.setClosedAt(ghIssue.getClosedAt().toInstant().atZone(ZoneOffset.UTC));
        }
        try {
            if (ghIssue.getClosedBy() != null) {
                findOrCreateGitHubUser(ghIssue.getClosedBy()).ifPresent(issue::setClosedBy);
            }
        } catch (IOException e) {
            log.warn("Cannot resolve closedBy user for issue #{}: {}", ghIssue.getNumber(), e.getMessage());
        }
        for (GHUser assignee : ghIssue.getAssignees()) {
            try {
                Optional<GitHubUser> user = findOrCreateGitHubUser(assignee);
                user.ifPresent(gitHubUser -> issue.getAssignees().add(gitHubUser));
            } catch (IOException e) {
                log.warn("Cannot resolve assignee for issue #{}: {}", ghIssue.getNumber(), e.getMessage());
            }
        }
        for (GHLabel label : ghIssue.getLabels()) {
            issue.getLabels().add(findOrCreateGitHubLabel(label));
        }
    }

    /**
     * Check for {@link GitHubUser}.
     *
     * @param ghUser The GitHub user information.
     * @return The retrieved or newly created descriptor instance.
     */
    public Optional<GitHubUser> findOrCreateGitHubUser(GHUser ghUser) throws IOException {
        if (null == ghUser) {
            log.warn("Cannot find or create 'null' user");
            return Optional.empty();
        }
        String userName = ghUser.getLogin();
        if (userName == null) {
            log.error("GitHub users must have a login (skipping!): '{}'", ghUser);
            return Optional.empty();
        }
        log.debug("Retrieving GitHubuser '{}' from cache", userName);
        Optional<GitHubUser> optionalGitHubUser = descriptorCache.getUser(userName);
        if (optionalGitHubUser.isPresent()) {
            return optionalGitHubUser;
        }
        return Optional.of(createGitHubUser(userName, ghUser));
    }

    private GitHubUser getGitHubUserFromDB(String userName) {
        log.debug("Retrieving GitHub user '{}' from DB", userName);
        String query = "MATCH (u:GitHub:User) WHERE u.username = $username RETURN u";
        try (Query.Result<Query.Result.CompositeRowObject> result = store.executeQuery(query, Map.of("username", userName))) {
            if (result.hasResult()) {
                return result.iterator().next().get("u", GitHubUser.class);
            }
        }
        return null;
    }

    private GitHubUser createGitHubUser(String userName, GHUser ghUser) {
        GitHubUser gitHubUser = getGitHubUserFromDB(userName);
        if (null != gitHubUser) {
            log.debug("Adding existing GitHub user to cache: '{}' ({} <{}>)", userName, gitHubUser.getName(), gitHubUser.getEmail());
            descriptorCache.put(gitHubUser);
            return gitHubUser;
        }

        String email = null;
        try {
            email = ghUser.getEmail();
        } catch (IOException e) {
            log.warn("Cannot retrieve email for '{}'", userName, e);
        }
        String name = null;
        try {
            name = ghUser.getName();
        } catch (IOException e) {
            log.warn("Cannot retrieve (real) name for '{}'", userName, e);
        }
        log.debug("Creating new GitHub user in DB: '{}' ({} <{}>)", userName, name, email);
        gitHubUser = store.create(GitHubUser.class);
        gitHubUser.setEmail(email);
        gitHubUser.setUsername(userName);
        gitHubUser.setName(name);

        log.debug("Adding new GitHub user to cache: '{}' ({} <{}>)", userName, name, email);
        descriptorCache.put(gitHubUser);

        return gitHubUser;
    }

    /**
     * Create or retrieve a placeholder {@link GitHubUser} from git-level commit info
     * (name/email) when the GitHub user API returns 404 for deleted or virtual users.
     * Uses email as the cache key (falls back to name if email is unavailable).
     */
    private Optional<GitHubUser> findOrCreatePlaceholderUser(String email, String name) {
        String key = email != null ? email : name;
        if (key == null) {
            return Optional.empty();
        }
        Optional<GitHubUser> cached = descriptorCache.getUser(key);
        if (cached.isPresent()) {
            return cached;
        }
        GitHubUser gitHubUser = getGitHubUserFromDB(key);
        if (gitHubUser != null) {
            descriptorCache.put(gitHubUser);
            return Optional.of(gitHubUser);
        }
        log.debug("Creating placeholder GitHub user: '{}' ({} <{}>)", key, name, email);
        gitHubUser = store.create(GitHubUser.class);
        gitHubUser.setUsername(key);
        gitHubUser.setName(name);
        gitHubUser.setEmail(email);
        descriptorCache.put(gitHubUser);
        return Optional.of(gitHubUser);
    }

    /**
     * Check for {@link GitHubLabel}.
     *
     * @param ghLabel The GitHub label information.
     * @return The retrieved or newly created descriptor instance.
     */
    public GitHubLabel findOrCreateGitHubLabel(GHLabel ghLabel) {
        Optional<GitHubLabel> optionalLabel = descriptorCache.getLabel(ghLabel.getName());

        return optionalLabel.orElseGet(() -> createGitHubLabel(ghLabel));
    }

    private GitHubLabel createGitHubLabel(GHLabel ghLabel) {
        log.debug("Creating new label: '{}@{}' ('{}' with color '{}')", ghLabel.getName(), ghLabel.getUrl(), ghLabel.getDescription(), ghLabel.getColor());
        GitHubLabel label = store.create(GitHubLabel.class);
        label.setName(ghLabel.getName());
        label.setDescription(ghLabel.getDescription());
        label.setColor(ghLabel.getColor());

        descriptorCache.put(label);

        return label;
    }

    /**
     * Check for {@link GitHubMilestone}.
     *
     * @param gHMilestone The GitHub milestone information.
     * @return The retrieved or newly created descriptor instance.
     */
    public GitHubMilestone findOrCreateGitHubMilestone(GHMilestone gHMilestone) {
        Optional<GitHubMilestone> milestone = descriptorCache.getMilestone(gHMilestone.getNumber());
        return milestone.orElseGet(() -> createGitHubMilestone(gHMilestone));
    }

    public GitTagDescriptor findOrCreateGitHubTag(GHTag ghTag, List<GHCommit> ghCommits) {
        Optional<GitTagDescriptor> tag = descriptorCache.getTag(ghTag.getName());
        return tag.orElseGet(() -> createGitHubTag(ghTag, ghCommits));
    }

    private GitTagDescriptor createGitHubTag(GHTag ghTag, List<GHCommit> ghCommits) {
        log.debug("Creating new tag: '{}' with commit '{}'", ghTag.getName(), ghTag.getCommit().getSHA1());
        List<GitCommitDescriptor> commits = addGitCommits(ghCommits, true);

        GitTagDescriptor tag = store.create(GitTagDescriptor.class);
        tag.setLabel(ghTag.getName());
        tag.setCommit(commits.get(0));
        descriptorCache.put(tag);
        return tag;
    }

    public GitHubRelease findOrCreateGitHubRelease(GHRelease ghRelease) {
        Optional<GitHubRelease> release = descriptorCache.getRelease(ghRelease.getName());
        return release.orElseGet(() -> createGitHubRelease(ghRelease));
    }

    private GitHubRelease createGitHubRelease(GHRelease ghRelease) {
        log.debug("Creating new release: {}", ghRelease);

        GitHubRelease release = store.create(GitHubRelease.class);
        release.setName(ghRelease.getName());
        release.setBody(ghRelease.getBody());
        Optional<GitTagDescriptor> optionalTag = descriptorCache.getTag(ghRelease.getTagName());
        optionalTag.ifPresent(release::setTag);
        descriptorCache.put(release);
        return release;
    }

    private GitHubMilestone createGitHubMilestone(GHMilestone ghMilestone) {
        log.debug("Creating new milestone: {}", ghMilestone);

        GitHubMilestone milestone = store.create(GitHubMilestone.class);
        milestone.setNumber(ghMilestone.getNumber());
        milestone.setTitle(ghMilestone.getTitle());
        milestone.setDescription(ghMilestone.getDescription());
        milestone.setState(ghMilestone.getState().name());

        try {
            milestone.setCreatedAt(ghMilestone.getCreatedAt().toInstant().atZone(ZoneOffset.UTC));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            milestone.setUpdatedAt(ghMilestone.getUpdatedAt().toInstant().atZone(ZoneOffset.UTC));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (ghMilestone.getDueOn() != null) {
            milestone.setDueOn(ghMilestone.getDueOn().toInstant().atZone(ZoneOffset.UTC));
        }

        try {
            if (ghMilestone.getCreator() != null) {
                findOrCreateGitHubUser(ghMilestone.getCreator()).ifPresent(milestone::setCreatedBy);
            }
        } catch (IOException e) {
            log.warn("Cannot resolve creator for milestone #{}: {}", ghMilestone.getNumber(), e.getMessage());
        }

        descriptorCache.put(milestone);

        return milestone;
    }

    private Optional<GitCommitDescriptor> findExistingGitCommit(GHCommit ghCommit) {
        var result = descriptorCache.getCommit(ghCommit.getSHA1());
        if (result.isEmpty()) {
            var dbResult = getCommitDescriptorFromDB(store, ghCommit.getSHA1());
            if (null == dbResult) {
                log.warn("Cannot find (expected) commit '{}'", ghCommit.getSHA1());
            } else {
                descriptorCache.put(dbResult);
                return Optional.of(dbResult);
            }
        }
        return result;
    }

    private GitCommitDescriptor findOrCreateGitCommit(GHCommit ghCommit) {
        Optional<GitCommitDescriptor> optionalCommit = descriptorCache.getCommit(ghCommit.getSHA1());
        return optionalCommit.orElseGet(() -> createGitCommitDescriptor(ghCommit));
    }

    private GitCommitDescriptor createGitCommitDescriptor(GHCommit ghCommit) {
        GitCommitDescriptor commit = getCommitDescriptorFromDB(store, ghCommit.getSHA1());
        if (commit == null) {
            log.debug("Adding commit '{}' from remote repository", ghCommit.getSHA1());
            commit = createGitHubCommit(ghCommit);
        } else {
            log.debug("Found existing commit '{}' from DB", ghCommit.getSHA1());
        }
        descriptorCache.put(commit);
        return commit;
    }

    private GitCommitDescriptor createGitHubCommit(GHCommit ghCommit) {
        GitCommitDescriptor commit = store.create(GitCommitDescriptor.class);
        commit.setSha(ghCommit.getSHA1());
        try {
            ghCommit.getCommitDate().toInstant().atZone(ZoneOffset.UTC);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            if (ghCommit.getAuthor() != null) {
                findOrCreateGitHubUser(ghCommit.getAuthor()).ifPresent(user -> commit.setAuthor(user.getName()));
            }
        } catch (IOException e) {
            log.warn("Cannot resolve GitHub author for commit '{}', falling back to git info: {}", ghCommit.getSHA1(), e.getMessage());
            try {
                GitUser gitAuthor = ghCommit.getCommitShortInfo().getAuthor();
                if (gitAuthor != null) {
                    findOrCreatePlaceholderUser(gitAuthor.getEmail(), gitAuthor.getName())
                        .ifPresent(user -> commit.setAuthor(user.getName()));
                }
            } catch (IOException e2) {
                log.warn("Cannot resolve git author info for commit '{}': {}", ghCommit.getSHA1(), e2.getMessage());
            }
        }
        try {
            if (ghCommit.getCommitter() != null) {
                findOrCreateGitHubUser(ghCommit.getCommitter()).ifPresent(user -> commit.setCommitter(user.getName()));
            }
        } catch (IOException e) {
            log.warn("Cannot resolve GitHub committer for commit '{}', falling back to git info: {}", ghCommit.getSHA1(), e.getMessage());
            try {
                GitUser gitCommitter = ghCommit.getCommitShortInfo().getCommitter();
                if (gitCommitter != null) {
                    findOrCreatePlaceholderUser(gitCommitter.getEmail(), gitCommitter.getName())
                        .ifPresent(user -> commit.setCommitter(user.getName()));
                }
            } catch (IOException e2) {
                log.warn("Cannot resolve git committer info for commit '{}': {}", ghCommit.getSHA1(), e2.getMessage());
            }
        }
        try {
            commit.setDate(ghCommit.getCommitDate().toString());
            // TODO Distinguish between commit and author date
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            for (GHCommit parent : ghCommit.getParents()) {
                log.debug("Adding '{}' as parent to '{}'", parent.getSHA1(), ghCommit.getSHA1());
                findExistingGitCommit(parent).ifPresent(parentCommit -> commit.getParents().add(parentCommit));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        descriptorCache.put(commit);
        return commit;
    }

    public GitBranchDescriptor findOrCreateGitHubBranch(GHBranch ghBranch, List<GHCommit> ghCommits) {
        Optional<GitBranchDescriptor> branch = descriptorCache.getBranch(ghBranch.getName());
        return branch.orElseGet(() -> createGitHubBranch(ghBranch, ghCommits));
    }

    private GitBranchDescriptor createGitHubBranch(GHBranch ghBranch, List<GHCommit> ghCommits) {
        log.debug("Creating new branch: '{}' with {} commits", ghBranch.getName(), ghCommits.size());
        List<GitCommitDescriptor> commits = addGitCommits(ghCommits, true);

        GitBranchDescriptor branch = store.create(GitBranchDescriptor.class);
        branch.setName(ghBranch.getName());
        branch.setHead(commits.get(0));
        descriptorCache.put(branch);
        return branch;
    }

    private List<GitCommitDescriptor> addGitCommits(List<GHCommit> ghCommits, boolean reverse) {
        log.debug("Adding {} remote commits to DB and cache", ghCommits.size());
        List<GitCommitDescriptor> commits = new ArrayList<>(ghCommits.size());
        if (reverse) {
            for (int i = ghCommits.size() - 1; i >= 0; i--) {
                commits.add(findOrCreateGitCommit(ghCommits.get(i)));
            }
        } else {
            for (GHCommit ghCommit : ghCommits) {
                commits.add(findOrCreateGitCommit(ghCommit));
            }
        }
        return commits;
    }

    // --- Cache DTO-based methods (Phase 1: file cache support) ---

    /**
     * Create or retrieve a commit descriptor from a cached DTO.
     */
    public GitCommitDescriptor findOrCreateCommitFromCache(CachedCommit cached) {
        Optional<GitCommitDescriptor> existing = descriptorCache.getCommit(cached.getSha());
        if (existing.isPresent()) {
            return existing.get();
        }
        GitCommitDescriptor fromDb = getCommitDescriptorFromDB(store, cached.getSha());
        if (fromDb != null) {
            descriptorCache.put(fromDb);
            return fromDb;
        }
        return createCommitFromCache(cached);
    }

    private GitCommitDescriptor createCommitFromCache(CachedCommit cached) {
        log.debug("Creating commit '{}' from file cache", cached.getSha());
        GitCommitDescriptor commit = store.create(GitCommitDescriptor.class);
        commit.setSha(cached.getSha());
        commit.setDate(cached.getDate());

        // Resolve author
        if (cached.getAuthorLogin() != null) {
            resolveUserFromCache(cached.getAuthorLogin(), cached.getAuthorName(), cached.getAuthorEmail())
                .ifPresent(user -> commit.setAuthor(user.getName()));
        } else if (cached.getAuthorEmail() != null || cached.getAuthorName() != null) {
            findOrCreatePlaceholderUser(cached.getAuthorEmail(), cached.getAuthorName())
                .ifPresent(user -> commit.setAuthor(user.getName()));
        }

        // Resolve committer
        if (cached.getCommitterLogin() != null) {
            resolveUserFromCache(cached.getCommitterLogin(), cached.getCommitterName(), cached.getCommitterEmail())
                .ifPresent(user -> commit.setCommitter(user.getName()));
        } else if (cached.getCommitterEmail() != null || cached.getCommitterName() != null) {
            findOrCreatePlaceholderUser(cached.getCommitterEmail(), cached.getCommitterName())
                .ifPresent(user -> commit.setCommitter(user.getName()));
        }

        // Resolve parents
        if (cached.getParentShas() != null) {
            for (String parentSha : cached.getParentShas()) {
                descriptorCache.getCommit(parentSha)
                    .or(() -> {
                        GitCommitDescriptor fromDb = getCommitDescriptorFromDB(store, parentSha);
                        if (fromDb != null) {
                            descriptorCache.put(fromDb);
                        }
                        return Optional.ofNullable(fromDb);
                    })
                    .ifPresent(parent -> commit.getParents().add(parent));
            }
        }

        descriptorCache.put(commit);
        return commit;
    }

    /**
     * Resolve a user by login/name/email, creating from cache info if needed.
     */
    private Optional<GitHubUser> resolveUserFromCache(String login, String name, String email) {
        if (login == null) {
            return Optional.empty();
        }
        Optional<GitHubUser> cached = descriptorCache.getUser(login);
        if (cached.isPresent()) {
            return cached;
        }
        GitHubUser fromDb = getGitHubUserFromDB(login);
        if (fromDb != null) {
            descriptorCache.put(fromDb);
            return Optional.of(fromDb);
        }
        log.debug("Creating user '{}' from file cache info", login);
        GitHubUser user = store.create(GitHubUser.class);
        user.setUsername(login);
        user.setName(name);
        user.setEmail(email);
        descriptorCache.put(user);
        return Optional.of(user);
    }

    /**
     * Create or retrieve a user descriptor from a cached DTO.
     */
    public Optional<GitHubUser> findOrCreateUserFromCache(CachedUser cachedUser) {
        if (cachedUser == null || cachedUser.getLogin() == null) {
            return Optional.empty();
        }
        return resolveUserFromCache(cachedUser.getLogin(), cachedUser.getName(), cachedUser.getEmail());
    }

    /**
     * Create a branch descriptor from cached data, loading commits from cache DTOs.
     */
    public GitBranchDescriptor findOrCreateBranchFromCache(CachedBranch cachedBranch, List<CachedCommit> cachedCommits) {
        Optional<GitBranchDescriptor> existing = descriptorCache.getBranch(cachedBranch.getName());
        if (existing.isPresent()) {
            return existing.get();
        }
        log.debug("Creating branch '{}' from file cache with {} commits", cachedBranch.getName(), cachedCommits.size());

        // Add commits in reverse order (oldest first) like the original code
        List<GitCommitDescriptor> commits = new ArrayList<>(cachedCommits.size());
        for (int i = cachedCommits.size() - 1; i >= 0; i--) {
            commits.add(findOrCreateCommitFromCache(cachedCommits.get(i)));
        }

        GitBranchDescriptor branch = store.create(GitBranchDescriptor.class);
        branch.setName(cachedBranch.getName());
        if (!commits.isEmpty()) {
            branch.setHead(commits.get(0));
        }
        descriptorCache.put(branch);
        return branch;
    }

    /**
     * Create a tag descriptor from cached data, loading commits from cache DTOs.
     */
    public GitTagDescriptor findOrCreateTagFromCache(CachedTag cachedTag, List<CachedCommit> cachedCommits) {
        Optional<GitTagDescriptor> existing = descriptorCache.getTag(cachedTag.getName());
        if (existing.isPresent()) {
            return existing.get();
        }
        log.debug("Creating tag '{}' from file cache with {} commits", cachedTag.getName(), cachedCommits.size());

        // Add commits in reverse order (oldest first) like the original code
        List<GitCommitDescriptor> commits = new ArrayList<>(cachedCommits.size());
        for (int i = cachedCommits.size() - 1; i >= 0; i--) {
            commits.add(findOrCreateCommitFromCache(cachedCommits.get(i)));
        }

        GitTagDescriptor tag = store.create(GitTagDescriptor.class);
        tag.setLabel(cachedTag.getName());
        if (!commits.isEmpty()) {
            tag.setCommit(commits.get(0));
        }
        descriptorCache.put(tag);
        return tag;
    }

    /**
     * Create a release descriptor from cached data.
     */
    public GitHubRelease findOrCreateReleaseFromCache(CachedRelease cachedRelease) {
        Optional<GitHubRelease> existing = descriptorCache.getRelease(cachedRelease.getName());
        if (existing.isPresent()) {
            return existing.get();
        }
        log.debug("Creating release '{}' from file cache", cachedRelease.getName());
        GitHubRelease release = store.create(GitHubRelease.class);
        release.setName(cachedRelease.getName());
        release.setBody(cachedRelease.getBody());
        if (cachedRelease.getTagName() != null) {
            descriptorCache.getTag(cachedRelease.getTagName()).ifPresent(release::setTag);
        }
        descriptorCache.put(release);
        return release;
    }

    // --- Phase 2: Cache DTO-based methods for issues, PRs, milestones ---

    /**
     * Create or retrieve an issue descriptor from a cached DTO.
     */
    public GitHubIssue findOrCreateIssueFromCache(CachedIssue cached) {
        Optional<GitHubIssue> existing = descriptorCache.getIssue(cached.getNumber());
        if (existing.isPresent()) {
            return existing.get();
        }
        log.debug("Creating issue #{} from file cache", cached.getNumber());
        GitHubIssue issue = store.create(GitHubIssue.class);
        populateIssueFromCache(issue, cached);
        descriptorCache.put(issue);
        return issue;
    }

    /**
     * Create or retrieve a pull request descriptor from a cached DTO.
     */
    public GitHubPullRequest findOrCreatePullRequestFromCache(CachedPullRequest cached) {
        Optional<GitHubPullRequest> existing = descriptorCache.getPullRequest(cached.getNumber());
        if (existing.isPresent()) {
            return existing.get();
        }
        log.debug("Creating PR #{} from file cache", cached.getNumber());
        GitHubPullRequest pr = store.create(GitHubPullRequest.class);
        populateIssueFromCache(pr, cached);

        if (cached.getMergedAt() != null) {
            pr.setMergedAt(ZonedDateTime.parse(cached.getMergedAt()));
        }
        if (cached.getBaseSha() != null) {
            descriptorCache.getCommit(cached.getBaseSha())
                .or(() -> {
                    GitCommitDescriptor fromDb = getCommitDescriptorFromDB(store, cached.getBaseSha());
                    if (fromDb != null) { descriptorCache.put(fromDb); }
                    return Optional.ofNullable(fromDb);
                })
                .ifPresent(pr::setBase);
        }
        if (cached.getHeadSha() != null) {
            descriptorCache.getCommit(cached.getHeadSha())
                .or(() -> {
                    GitCommitDescriptor fromDb = getCommitDescriptorFromDB(store, cached.getHeadSha());
                    if (fromDb != null) { descriptorCache.put(fromDb); }
                    return Optional.ofNullable(fromDb);
                })
                .ifPresent(pr::setHead);
        }

        // Add PR commits to descriptor cache
        if (cached.getCommitShas() != null) {
            for (String sha : cached.getCommitShas()) {
                findOrCreateCommitFromCacheBySha(sha);
            }
        }

        descriptorCache.put(pr);
        return pr;
    }

    /**
     * Create or retrieve a milestone descriptor from a cached DTO.
     */
    public GitHubMilestone findOrCreateMilestoneFromCache(CachedMilestone cached) {
        Optional<GitHubMilestone> existing = descriptorCache.getMilestone(cached.getNumber());
        if (existing.isPresent()) {
            return existing.get();
        }
        log.debug("Creating milestone #{} from file cache", cached.getNumber());
        GitHubMilestone milestone = store.create(GitHubMilestone.class);
        milestone.setNumber(cached.getNumber());
        milestone.setTitle(cached.getTitle());
        milestone.setDescription(cached.getDescription());
        milestone.setState(cached.getState());

        if (cached.getCreatedAt() != null) {
            milestone.setCreatedAt(ZonedDateTime.parse(cached.getCreatedAt()));
        }
        if (cached.getUpdatedAt() != null) {
            milestone.setUpdatedAt(ZonedDateTime.parse(cached.getUpdatedAt()));
        }
        if (cached.getDueOn() != null) {
            milestone.setDueOn(ZonedDateTime.parse(cached.getDueOn()));
        }
        if (cached.getCreatorLogin() != null) {
            resolveUserFromCache(cached.getCreatorLogin(), null, null)
                .ifPresent(milestone::setCreatedBy);
        }

        descriptorCache.put(milestone);
        return milestone;
    }

    /**
     * Populate common issue fields from a cached DTO onto a descriptor.
     */
    private void populateIssueFromCache(GitHubIssue issue, CachedIssue cached) {
        issue.setNumber(cached.getNumber());
        issue.setTitle(cached.getTitle());
        issue.setBody(cached.getBody());
        issue.setState(cached.getState());
        issue.setLocked(cached.isLocked());

        if (cached.getCreatedAt() != null) {
            issue.setCreatedAt(ZonedDateTime.parse(cached.getCreatedAt()));
        }
        if (cached.getUpdatedAt() != null) {
            issue.setUpdatedAt(ZonedDateTime.parse(cached.getUpdatedAt()));
        }
        if (cached.getClosedAt() != null) {
            issue.setClosedAt(ZonedDateTime.parse(cached.getClosedAt()));
        }
        if (cached.getCreatedByLogin() != null) {
            resolveUserFromCache(cached.getCreatedByLogin(), null, null)
                .ifPresent(issue::setCreatedBy);
        }
        if (cached.getClosedByLogin() != null) {
            resolveUserFromCache(cached.getClosedByLogin(), null, null)
                .ifPresent(issue::setClosedBy);
        }
        if (cached.getAssigneeLogins() != null) {
            for (String login : cached.getAssigneeLogins()) {
                resolveUserFromCache(login, null, null)
                    .ifPresent(user -> issue.getAssignees().add(user));
            }
        }
        if (cached.getLabelNames() != null) {
            for (String labelName : cached.getLabelNames()) {
                descriptorCache.getLabel(labelName)
                    .ifPresent(label -> issue.getLabels().add(label));
            }
        }
        if (cached.getMilestoneNumber() != null) {
            descriptorCache.getMilestone(cached.getMilestoneNumber())
                .ifPresent(issue::setMilestone);
        }
    }

    /**
     * Find or create a commit descriptor by SHA, checking descriptor cache and DB.
     */
    private GitCommitDescriptor findOrCreateCommitFromCacheBySha(String sha) {
        Optional<GitCommitDescriptor> existing = descriptorCache.getCommit(sha);
        if (existing.isPresent()) {
            return existing.get();
        }
        GitCommitDescriptor fromDb = getCommitDescriptorFromDB(store, sha);
        if (fromDb != null) {
            descriptorCache.put(fromDb);
            return fromDb;
        }
        // Create a minimal commit descriptor if not found
        log.debug("Creating minimal commit '{}' from SHA reference", sha);
        GitCommitDescriptor commit = store.create(GitCommitDescriptor.class);
        commit.setSha(sha);
        descriptorCache.put(commit);
        return commit;
    }
}
