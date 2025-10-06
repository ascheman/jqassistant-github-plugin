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

import java.io.IOException;
import java.time.ZoneOffset;
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

    public GitHubPullRequest findOrCreatePullRequest(GHPullRequest ghPullRequest) {
        // A pull request is a special kind of issue
        return this.descriptorCache.getPullRequest(ghPullRequest.getNumber())
            .orElseGet(() -> createGitHubPullRequest(ghPullRequest));
    }

    private GitHubPullRequest createGitHubPullRequest(GHPullRequest ghPullRequest) {
        log.debug("Creating new pull request: {}", ghPullRequest);
        GitHubPullRequest pullRequest = store.create(GitHubPullRequest.class);
        if (ghPullRequest.getMergedAt() != null) {
            pullRequest.setMergedAt(ghPullRequest.getMergedAt().toInstant().atZone(ZoneOffset.UTC));
        }
        try {
            pullRequest.setBase(findGitCommit(ghPullRequest.getBase().getCommit()));
        } catch (IOException e) {
            log.warn("Cannot retrieve Base Commit '{}' for '{}#{}'",
                ghPullRequest.getBase().getRef(),
                ghPullRequest.getHtmlUrl(),
                ghPullRequest.getId()
            );
        }
        try {
            var head = ghPullRequest.getHead();
            if (null != head) {
                if (null == head.getRepository()) {
                    log.warn("Cannot find Head Commit '{}' of '{}#{}'",
                        head.getRef(),
                        ghPullRequest.getHtmlUrl(),
                        ghPullRequest.getId()
                    );
                } else {
                    pullRequest.setHead(findGitCommit(head.getCommit()));
                }
            } else {
                log.warn("Cannot find Head Commit for '{}#{}'",
                    ghPullRequest.getHtmlUrl(),
                    ghPullRequest.getId()
                );
            }
        } catch (IOException e) {
            log.warn("Cannot retrieve Head Commit '{}' for '{}#{}'",
                ghPullRequest.getHead().getRef(),
                ghPullRequest.getHtmlUrl(),
                ghPullRequest.getId()
            );
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
        log.debug("Creating new issue: " + ghIssue);
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
            throw new RuntimeException(e);
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
            throw new RuntimeException(e);
        }
        for (GHUser assignee : ghIssue.getAssignees()) {
            try {
                Optional<GitHubUser> user = findOrCreateGitHubUser(assignee);
                user.ifPresent(gitHubUser -> issue.getAssignees().add(gitHubUser));
            } catch (IOException e) {
                throw new RuntimeException(e);
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
        return Optional.ofNullable(createGitHubUser(userName, ghUser));
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
        log.debug("Creating new label: " + ghLabel);
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

    public GitTagDescriptor findOrCreateGitHubTag(GHTag ghTag) {
        Optional<GitTagDescriptor> tag = descriptorCache.getTag(ghTag.getName());
        return tag.orElseGet(() -> createGitHubTag(ghTag));
    }

    private GitTagDescriptor createGitHubTag(GHTag ghTag) {
        log.debug("Creating new tag: {}", ghTag);

        GitTagDescriptor tag = store.create(GitTagDescriptor.class);
        tag.setLabel(ghTag.getName());
        GitCommitDescriptor commit = findGitCommit(ghTag.getCommit());
        tag.setCommit(commit);
        descriptorCache.put(tag);
        return tag;
    }

    public GitHubRelease findOrCreateGitHubRelease(GHRelease ghRelease) {
        Optional<GitHubRelease> release = descriptorCache.getRelease(ghRelease.getName());
        return release.orElseGet(() -> createGitHubRelease(ghRelease));
    }

    private GitHubRelease createGitHubRelease(GHRelease ghRelease) {
        log.debug("Creating new release: " + ghRelease);

        GitHubRelease release = store.create(GitHubRelease.class);
        release.setName(ghRelease.getName());
        release.setBody(ghRelease.getBody());
        Optional<GitTagDescriptor> optionalTag = descriptorCache.getTag(ghRelease.getTagName());
        optionalTag.ifPresent(release::setTag);
        descriptorCache.put(release);
        return release;
    }

    private GitHubMilestone createGitHubMilestone(GHMilestone ghMilestone) {
        log.debug("Creating new milestone: " + ghMilestone);

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
            throw new RuntimeException(e);
        }

        descriptorCache.put(milestone);

        return milestone;
    }

    public GitCommitDescriptor findGitCommit(GHCommit ghCommit) {
        Optional<GitCommitDescriptor> optionalCommit = descriptorCache.getCommit(ghCommit.getSHA1());
        return optionalCommit.orElseGet(() -> createGitCommitDescriptor(ghCommit));
    }

    private GitCommitDescriptor createGitCommitDescriptor(GHCommit ghCommit) {
        GitCommitDescriptor commit = getCommitDescriptorFromDB(store, ghCommit.getSHA1());
        if (commit == null) {
            log.debug("Using '{}' from remote repository", ghCommit.getSHA1());
            commit = createGitHubCommit(ghCommit);
        } else {
            log.debug("Found existing commit '{}'", ghCommit.getSHA1());
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
            throw new RuntimeException(e);
        }
        try {
            if (ghCommit.getCommitter() != null) {
                findOrCreateGitHubUser(ghCommit.getCommitter()).ifPresent(user -> commit.setCommitter(user.getName()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            commit.setDate(ghCommit.getCommitDate().toString());
            // TODO Distinguish between commit and author date
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            for (GHCommit parent : ghCommit.getParents()) {
                commit.getParents().add(findGitCommit(parent));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        descriptorCache.put(commit);
        return commit;
    }

    public GitBranchDescriptor findOrCreateGitHubBranch(GHBranch ghBranch, List<GHCommit> ghCommits) {
        log.debug("Creating new branch: {}", ghBranch.getName());
        GitBranchDescriptor branch = store.create(GitBranchDescriptor.class);
        branch.setName(ghBranch.getName());
        for (GHCommit ghCommit : ghCommits) {
            findGitCommit(ghCommit);
        }
        branch.setHead(findGitCommit(ghCommits.get(0)));
        return branch;
    }
}
