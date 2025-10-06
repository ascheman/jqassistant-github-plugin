package org.jqassistant.plugin.github.cache;


import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitCommitDescriptor;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitTagDescriptor;
import lombok.Getter;
import lombok.Setter;
import org.jqassistant.plugin.github.model.GitHubIssue;
import org.jqassistant.plugin.github.model.GitHubLabel;
import org.jqassistant.plugin.github.model.GitHubMilestone;
import org.jqassistant.plugin.github.model.GitHubPullRequest;
import org.jqassistant.plugin.github.model.GitHubRelease;
import org.jqassistant.plugin.github.model.GitHubRepository;
import org.jqassistant.plugin.github.model.GitHubUser;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * This class caches descriptor instances which have already been created.
 * <p>
 * For more information see {@link CacheEndpoint} which is the public accessible interface for this cache.
 */
class DescriptorCache {

    @Setter
    @Getter
    private GitHubRepository repository;

    private final Map<String, GitCommitDescriptor> commits = new TreeMap<>();
    private final Map<Integer, GitHubIssue> issues = new TreeMap<>();
    private final Map<String, GitHubUser> users = new TreeMap<>();
    private final Map<String, GitHubLabel> labels = new TreeMap<>();
    private final Map<Integer, GitHubMilestone> milestones = new TreeMap<>();
    private final Map<String, GitHubRelease> releases = new TreeMap<>();
    private final Map<String, GitTagDescriptor> tags = new TreeMap<>();
    private final Map<String, GitBranchDescriptor> branches = new TreeMap<>();

    Optional<GitHubMilestone> getMilestone(Integer milestoneID) {
        return Optional.ofNullable(milestones.get(milestoneID));
    }

    Optional<GitHubUser> getUser(String userName) {
        return Optional.ofNullable(users.get(userName));
    }

    Optional<GitHubLabel> getLabel(String labelID) {
        return Optional.ofNullable(labels.get(labelID));
    }

    Optional<GitCommitDescriptor> getCommit(String commitID) {
        return Optional.ofNullable(commits.get(commitID));
    }

    Optional<GitHubIssue> getIssue(Integer issueID) {
        return Optional.ofNullable(issues.get(issueID));
    }

    Optional<GitHubPullRequest> getPullRequest(Integer pullRequestId) {
        return getIssue(pullRequestId).filter(i -> i instanceof GitHubPullRequest).map(i -> (GitHubPullRequest) i);
    }

    Optional<GitHubRelease> getRelease(String releaseName) {
        return Optional.ofNullable(releases.get(releaseName));
    }

    Optional<GitTagDescriptor> getTag(String tagName) {
        return Optional.ofNullable(tags.get(tagName));
    }

    void put(GitHubUser user) {
        this.users.putIfAbsent(user.getUsername(), user);
    }

    void put(GitHubLabel label) {
        this.labels.putIfAbsent(label.getName(), label);
    }

    void put(GitHubMilestone milestone) {
        milestones.putIfAbsent(milestone.getNumber(), milestone);
    }

    void put(GitCommitDescriptor commit) {
        this.commits.putIfAbsent(commit.getSha(), commit);
    }

    void put(GitHubIssue issue) {
        this.issues.putIfAbsent(issue.getNumber(), issue);
    }

    void put(GitHubRelease release) {
        this.releases.putIfAbsent(release.getName(), release);
    }

    void put(GitTagDescriptor tag) {
        this.tags.putIfAbsent(tag.getLabel(), tag);
    }

}
