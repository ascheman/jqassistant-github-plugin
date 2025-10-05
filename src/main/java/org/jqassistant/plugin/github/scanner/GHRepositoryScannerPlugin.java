package org.jqassistant.plugin.github.scanner;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import org.jqassistant.plugin.github.cache.CacheEndpoint;
import org.jqassistant.plugin.github.model.GitHubRepository;
import org.kohsuke.github.GHRepository;

import java.io.IOException;

public class GHRepositoryScannerPlugin extends AbstractScannerPlugin<GHRepository, GitHubRepository> {

    @Override
    public boolean accepts(GHRepository repository, String path, Scope scope) {
        return true;
    }

    @Override
    public GitHubRepository scan(GHRepository ghRepository, String path, Scope scope, Scanner scanner) throws IOException {
        CacheEndpoint cacheEndpoint = new CacheEndpoint(getScannerContext().getStore());

        GraphBuilder graphBuilder = new GraphBuilder(cacheEndpoint);
        return graphBuilder.scanRepository(ghRepository);
    }

}
