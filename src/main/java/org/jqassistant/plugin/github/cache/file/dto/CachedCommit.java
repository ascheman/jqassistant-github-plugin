package org.jqassistant.plugin.github.cache.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedCommit {

    private String sha;
    private String authorLogin;
    private String authorName;
    private String authorEmail;
    private String committerLogin;
    private String committerName;
    private String committerEmail;
    private String date;
    private List<String> parentShas;
}
