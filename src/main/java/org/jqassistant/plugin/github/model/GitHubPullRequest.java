package org.jqassistant.plugin.github.model;

import com.buschmais.xo.neo4j.api.annotation.Label;
import com.buschmais.xo.neo4j.api.annotation.Property;
import com.buschmais.xo.neo4j.api.annotation.Relation;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitCommitDescriptor;

import java.time.ZonedDateTime;

@Label("PullRequest")
public interface GitHubPullRequest extends GitHubIssue {

    @Property("mergedAt")
    ZonedDateTime getMergedAt();
    void setMergedAt(ZonedDateTime mergedAt);

    @Relation("HAS_BASE")
    GitCommitDescriptor getBase();
    void setBase(GitCommitDescriptor base);

    @Relation("HAS_HEAD")
    GitCommitDescriptor getHead();
    void setHead(GitCommitDescriptor head);
}
