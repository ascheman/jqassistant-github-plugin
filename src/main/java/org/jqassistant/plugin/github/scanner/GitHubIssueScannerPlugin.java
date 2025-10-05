package org.jqassistant.plugin.github.scanner;

import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.plugin.common.impl.scanner.AbstractUriScannerPlugin;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public class GitHubIssueScannerPlugin extends AbstractUriScannerPlugin<GHRepository> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubIssueScannerPlugin.class);

    @Override
    public boolean accepts(URI uri, String path, Scope scope) {
        return GitHubScope.REPOSITORY == scope;
    }

    @Override
    protected Optional<GHRepository> getResource(URI uri, ScannerContext context) {
        return resolve(uri, () -> connect(uri), context);
    }

    private GHRepository connect(URI uri) {
        String uriString = uri.toString();
        if (uriString.endsWith(".git")) {
            LOGGER.warn("Repository URI ('{}') must not end with '.git', cutting this off", uriString);
            try {
                uri = new URI(uriString.substring(0, uriString.length() - 4));
            } catch (URISyntaxException e) {
                throw new RuntimeException(String.format("This should never happen, as the String comes from valid URI: '%s'", uriString), e);
            }
        }
        try {
            GitHub gitHub = GitHub.connect();
            return connect(gitHub, uri);
        } catch (IOException e) {
            try {
                LOGGER.warn("Could not connect authenticated: {} - retrying anonymously", e.getMessage());
                GitHub gitHub = GitHub.connectAnonymously();
                return connect(gitHub, uri);
            } catch (IOException ee) {
                throw new RuntimeException("Unable to connect to GitHub repository", ee);
            }
        }
    }

    private GHRepository connect(GitHub gitHub, URI uri) throws IOException {
        String[] repoSplit = uri.getPath().split("/");
        if (repoSplit.length != 3) { // leading /
            LOGGER.error("Invalid GitHub repository URL specified. Needs to be <owner>/<name>");
            return null;
        }
        return gitHub.getRepository(repoSplit[1] + "/" + repoSplit[2]);
    }
}
