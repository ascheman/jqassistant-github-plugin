package org.jqassistant.plugin.github.scanner;

import com.buschmais.jqassistant.core.scanner.api.ScannerContext;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.plugin.common.impl.scanner.AbstractUriScannerPlugin;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public class GitHubIssueScannerPlugin extends AbstractUriScannerPlugin<GHRepository> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubIssueScannerPlugin.class);

    private static final String PROPERTY_CACHE_DIR = "jqassistant.plugin.github.cache.dir";
    private static final String DEFAULT_CACHE_DIR = ".github-cache";
    private static final String PROPERTY_HTTP_CACHE_DIR = "jqassistant.plugin.github.http.cache.dir";
    private static final String PROPERTY_HTTP_CACHE_SIZE_MB = "jqassistant.plugin.github.http.cache.size.mb";
    private static final long DEFAULT_HTTP_CACHE_SIZE_MB = 50;

    @Override
    public boolean accepts(URI uri, String path, Scope scope) {
        return GitHubScope.REPOSITORY == scope;
    }

    @Override
    protected Optional<GHRepository> getResource(URI uri, ScannerContext context) {
        return resolve(uri, () -> connect(uri), context);
    }

    private OkHttpGitHubConnector createConnector() {
        String httpCacheDir = System.getProperty(PROPERTY_HTTP_CACHE_DIR);
        if (httpCacheDir == null) {
            String baseCacheDir = System.getProperty(PROPERTY_CACHE_DIR, DEFAULT_CACHE_DIR);
            httpCacheDir = baseCacheDir + File.separator + "http";
        }
        long cacheSizeMb = DEFAULT_HTTP_CACHE_SIZE_MB;
        String sizeProp = System.getProperty(PROPERTY_HTTP_CACHE_SIZE_MB);
        if (sizeProp != null) {
            try {
                cacheSizeMb = Long.parseLong(sizeProp);
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid value for '{}': '{}', using default {}MB", PROPERTY_HTTP_CACHE_SIZE_MB, sizeProp, DEFAULT_HTTP_CACHE_SIZE_MB);
            }
        }
        File cacheDirFile = new File(httpCacheDir);
        Cache cache = new Cache(cacheDirFile, cacheSizeMb * 1024 * 1024);
        LOGGER.info("OkHttp ETag cache enabled: dir='{}', maxSize={}MB", httpCacheDir, cacheSizeMb);
        OkHttpClient client = new OkHttpClient.Builder()
            .cache(cache)
            .build();
        return new OkHttpGitHubConnector(client);
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

        OkHttpGitHubConnector connector = createConnector();

        try {
            GitHub gitHub = new GitHubBuilder()
                .fromPropertyFile()
                .fromEnvironment()
                .withConnector(connector)
                .build();
            return connect(gitHub, uri);
        } catch (IOException e) {
            try {
                LOGGER.warn("Could not connect authenticated: {} - retrying anonymously", e.getMessage());
                GitHub gitHub = new GitHubBuilder()
                    .withConnector(connector)
                    .build();
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
