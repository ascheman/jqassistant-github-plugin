package org.jqassistant.plugin.github.cache.file;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.jqassistant.plugin.github.cache.file.dto.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * File-based cache store for GitHub API responses.
 * Uses JSON serialization with 2-character prefix sharding for commits and users.
 * All writes are atomic (write to .tmp, then rename).
 */
@Slf4j
public class FileCacheStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path cacheRoot;
    private final String owner;
    private final String repo;
    private final long userTtlDays;

    public FileCacheStore(Path cacheRoot, String owner, String repo, long userTtlDays) {
        this.cacheRoot = cacheRoot;
        this.owner = owner;
        this.repo = repo;
        this.userTtlDays = userTtlDays;
    }

    // --- Commit operations ---

    private Path commitPath(String sha) {
        String prefix = sha.substring(0, 2);
        return repoDir().resolve("commits").resolve(prefix).resolve(sha + ".json");
    }

    public boolean hasCommit(String sha) {
        return Files.exists(commitPath(sha));
    }

    public Optional<CachedCommit> readCommit(String sha) {
        return readJson(commitPath(sha), CachedCommit.class);
    }

    public void writeCommit(CachedCommit commit) {
        writeJson(commitPath(commit.getSha()), commit);
    }

    // --- User operations ---

    private Path userPath(String login) {
        String prefix = login.length() >= 2 ? login.substring(0, 2).toLowerCase() : login.toLowerCase();
        return cacheRoot.resolve("users").resolve(prefix).resolve(login + ".json");
    }

    public Optional<CachedUser> readUser(String login) {
        Optional<CachedUser> user = readJson(userPath(login), CachedUser.class);
        if (user.isPresent() && isUserExpired(user.get())) {
            log.debug("Cached user '{}' has expired (TTL {} days)", login, userTtlDays);
            return Optional.empty();
        }
        return user;
    }

    public void writeUser(CachedUser user) {
        writeJson(userPath(user.getLogin()), user);
    }

    private boolean isUserExpired(CachedUser user) {
        if (user.getCachedAt() == null) {
            return true;
        }
        ZonedDateTime cachedAt = ZonedDateTime.parse(user.getCachedAt());
        ZonedDateTime expiry = cachedAt.plusDays(userTtlDays);
        return ZonedDateTime.now(ZoneOffset.UTC).isAfter(expiry);
    }

    // --- Branch operations ---

    private Path branchPath(String name) {
        return repoDir().resolve("branches").resolve(name + ".json");
    }

    public Optional<CachedBranch> readBranch(String name) {
        return readJson(branchPath(name), CachedBranch.class);
    }

    public void writeBranch(CachedBranch branch) {
        writeJson(branchPath(branch.getName()), branch);
    }

    // --- Tag operations ---

    private Path tagPath(String name) {
        return repoDir().resolve("tags").resolve(name + ".json");
    }

    public Optional<CachedTag> readTag(String name) {
        return readJson(tagPath(name), CachedTag.class);
    }

    public void writeTag(CachedTag tag) {
        writeJson(tagPath(tag.getName()), tag);
    }

    // --- Release operations ---

    private Path releasePath(String name) {
        return repoDir().resolve("releases").resolve(name + ".json");
    }

    public Optional<CachedRelease> readRelease(String name) {
        return readJson(releasePath(name), CachedRelease.class);
    }

    public void writeRelease(CachedRelease release) {
        writeJson(releasePath(release.getName()), release);
    }

    // --- Label operations ---

    private Path labelPath(String name) {
        return repoDir().resolve("labels").resolve(name + ".json");
    }

    public Optional<CachedLabel> readLabel(String name) {
        return readJson(labelPath(name), CachedLabel.class);
    }

    public void writeLabel(CachedLabel label) {
        writeJson(labelPath(label.getName()), label);
    }

    // --- Scan metadata ---

    private Path metadataPath() {
        return repoDir().resolve("meta.json");
    }

    public ScanMetadata readMetadata() {
        return readJson(metadataPath(), ScanMetadata.class)
            .orElseGet(() -> ScanMetadata.builder().build());
    }

    public void writeMetadata(ScanMetadata metadata) {
        writeJson(metadataPath(), metadata);
    }

    // --- Internal helpers ---

    private Path repoDir() {
        return cacheRoot.resolve("repos").resolve(owner).resolve(repo);
    }

    private <T> Optional<T> readJson(Path path, Class<T> type) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            T value = MAPPER.readValue(path.toFile(), type);
            return Optional.of(value);
        } catch (IOException e) {
            log.warn("Failed to read cache file '{}': {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), value);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to write cache file '{}': {}", path, e.getMessage());
        }
    }

    /**
     * Returns the current timestamp as ISO-8601 string for use in metadata.
     */
    public static String nowTimestamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toString();
    }
}
