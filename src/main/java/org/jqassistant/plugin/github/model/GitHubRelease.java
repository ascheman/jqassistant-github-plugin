package org.jqassistant.plugin.github.model;

import com.buschmais.xo.neo4j.api.annotation.Label;
import com.buschmais.xo.neo4j.api.annotation.Property;
import com.buschmais.xo.neo4j.api.annotation.Relation;
import de.kontext_e.jqassistant.plugin.git.store.descriptor.GitTagDescriptor;

@Label("Release")
public interface GitHubRelease extends GitHub {
    @Property("body")
    String getBody();
    void setBody(String body);
    @Property("name")
    String getName();
    void setName(String text);

    @Relation("REFERENCES_TAG")
    GitTagDescriptor getTag();
    void setTag(GitTagDescriptor tag);
}
