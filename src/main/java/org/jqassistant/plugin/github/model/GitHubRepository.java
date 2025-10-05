package org.jqassistant.plugin.github.model;

import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.common.api.model.NamedDescriptor;
import com.buschmais.xo.neo4j.api.annotation.Label;
import com.buschmais.xo.neo4j.api.annotation.Property;
import com.buschmais.xo.neo4j.api.annotation.Relation;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitBranchDescriptor;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitTagDescriptor;

import java.util.List;

@Label("Repository")
public interface GitHubRepository extends GitHub, Descriptor, NamedDescriptor {

    @Property("owner")
    String getOwner();
    void setOwner(String owner);

    @Property("name")
    String getName();
    void setName(String name);

    @Relation("HAS_ISSUE")
    List<GitHubIssue> getIssues();

    @Relation("HAS_MILESTONE")
    List<GitHubMilestone> getMilestones();

    @Relation("HAS_RELEASE")
    List<GitHubRelease> getReleases();

    @Relation("HAS_TAG")
    List<GitTagDescriptor> getTags();

    @Relation("HAS_PULL_REQUEST")
    List<GitHubPullRequest> getPullRequests();

    @Relation("HAS_BRANCH")
    List<GitBranchDescriptor> getBranches();
}
