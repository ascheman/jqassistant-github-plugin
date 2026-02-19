package org.jqassistant.plugin.github.scanner;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import org.jqassistant.plugin.github.cache.CacheEndpoint;
import org.jqassistant.plugin.github.cache.file.FileCacheStore;
import org.jqassistant.plugin.github.model.GitHubRepository;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GHRepositoryScannerPlugin extends AbstractScannerPlugin<GHRepository, GitHubRepository> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GHRepositoryScannerPlugin.class);

    private static final String PROPERTY_CACHE_DIR = "jqassistant.plugin.github.cache.dir";
    private static final String PROPERTY_CACHE_ENABLED = "jqassistant.plugin.github.cache.enabled";
    private static final String PROPERTY_CACHE_USER_TTL = "jqassistant.plugin.github.cache.user.ttl.days";

    private static final String DEFAULT_CACHE_DIR = ".github-cache";
    private static final long DEFAULT_USER_TTL_DAYS = 7;

    @Override
    public boolean accepts(GHRepository repository, String path, Scope scope) {
        return true;
    }

    @Override
    public GitHubRepository scan(GHRepository ghRepository, String path, Scope scope, Scanner scanner) throws IOException {
        CacheEndpoint cacheEndpoint = new CacheEndpoint(getScannerContext().getStore());
        FileCacheStore fileCacheStore = createFileCacheStore(ghRepository);

        GraphBuilder graphBuilder = new GraphBuilder(cacheEndpoint, fileCacheStore);
        return graphBuilder.scanRepository(ghRepository);
    }

    private FileCacheStore createFileCacheStore(GHRepository ghRepository) {
        boolean enabled = Boolean.parseBoolean(System.getProperty(PROPERTY_CACHE_ENABLED, "true"));
        if (!enabled) {
            LOGGER.info("File cache disabled via system property '{}'", PROPERTY_CACHE_ENABLED);
            return null;
        }

        String cacheDirStr = System.getProperty(PROPERTY_CACHE_DIR, DEFAULT_CACHE_DIR);
        Path cacheDir = Paths.get(cacheDirStr);

        long userTtl;
        try {
            userTtl = Long.parseLong(System.getProperty(PROPERTY_CACHE_USER_TTL, String.valueOf(DEFAULT_USER_TTL_DAYS)));
        } catch (NumberFormatException e) {
            userTtl = DEFAULT_USER_TTL_DAYS;
        }

        String owner = ghRepository.getOwnerName();
        String repo = ghRepository.getName();

        LOGGER.info("File cache enabled: dir='{}', owner='{}', repo='{}', userTTL={}d", cacheDir, owner, repo, userTtl);
        return new FileCacheStore(cacheDir, owner, repo, userTtl);
    }
}
