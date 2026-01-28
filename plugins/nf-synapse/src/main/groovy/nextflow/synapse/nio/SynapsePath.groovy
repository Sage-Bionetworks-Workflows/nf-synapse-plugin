package nextflow.synapse.nio

import groovy.transform.CompileStatic

import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.Objects
import java.util.regex.Pattern

/**
 * Path implementation for Synapse URIs.
 *
 * Supports formats:
 * - syn://syn1234567 (latest version of file or folder entity)
 * - syn://syn1234567.5 (specific version of file entity)
 * - syn://syn1234567/filename.txt (file within a folder - for uploads)
 */
@CompileStatic
class SynapsePath implements Path {

    // Pattern for entity-only URIs: syn1234567 or syn1234567.5
    private static final Pattern SYNAPSE_ID_PATTERN = ~/^syn(\d+)(\.(\d+))?$/

    // Pattern for folder/filename URIs: syn1234567/filename.txt
    private static final Pattern SYNAPSE_FOLDER_FILE_PATTERN = ~/^syn(\d+)\/(.+)$/

    private final SynapseFileSystem fileSystem
    private final String synId
    private final Integer version
    private final String fileName  // null for entity-only paths, set for folder/file paths
    private final String originalUri
    private volatile String cachedFileName

    SynapsePath(SynapseFileSystem fileSystem, String uri) {
        this.fileSystem = fileSystem
        this.originalUri = uri

        String path = uri
        if (uri.startsWith('syn://')) {
            path = uri.substring(6)
        }
        // Remove any trailing slashes
        path = path.replaceAll('/+$', '')

        // Try folder/file pattern first (syn1234567/filename.txt)
        def folderFileMatcher = SYNAPSE_FOLDER_FILE_PATTERN.matcher(path)
        if (folderFileMatcher.matches()) {
            this.synId = "syn${folderFileMatcher.group(1)}"
            this.fileName = folderFileMatcher.group(2)
            this.version = null
            return
        }

        // Try entity-only pattern (syn1234567 or syn1234567.5)
        def matcher = SYNAPSE_ID_PATTERN.matcher(path)
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid Synapse URI: ${uri}. " +
                "Expected format: syn://syn1234567, syn://syn1234567.5, or syn://syn1234567/filename.txt"
            )
        }

        this.synId = "syn${matcher.group(1)}"
        this.version = matcher.group(3) ? Integer.parseInt(matcher.group(3)) : null
        this.fileName = null
    }

    SynapsePath(SynapseFileSystem fileSystem, String synId, Integer version) {
        this.fileSystem = fileSystem
        this.synId = synId
        this.version = version
        this.fileName = null
        this.originalUri = version != null ? "syn://${synId}.${version}" : "syn://${synId}"
    }

    /**
     * Private constructor for creating paths with fileName.
     */
    private SynapsePath(SynapseFileSystem fileSystem, String synId, Integer version, String fileName, String originalUri) {
        this.fileSystem = fileSystem
        this.synId = synId
        this.version = version
        this.fileName = fileName
        this.originalUri = originalUri
    }

    /**
     * Get the actual filename from Synapse entity metadata.
     * Caches the result for subsequent calls.
     */
    /**
     * Get the actual filename for this path.
     * For folder/file paths, returns the fileName directly.
     * For entity-only paths, fetches from Synapse (cached).
     */
    private String getActualFileName() {
        // If this is a folder/file path, use the explicit fileName
        if (fileName != null) {
            return fileName
        }

        // For entity-only paths, fetch from Synapse
        if (cachedFileName == null) {
            synchronized (this) {
                if (cachedFileName == null) {
                    try {
                        def entity = fileSystem.client.getEntity(synId, version)
                        cachedFileName = entity.name as String
                    } catch (Exception e) {
                        // Fall back to synId if we can't fetch metadata
                        cachedFileName = synId
                    }
                }
            }
        }
        return cachedFileName
    }

    String getSynId() {
        return synId
    }

    Integer getVersion() {
        return version
    }

    boolean hasVersion() {
        return version != null
    }

    /**
     * Check if this path represents a file within a folder (has a fileName component).
     */
    boolean isFolderFilePath() {
        return fileName != null
    }

    /**
     * Get the fileName component for folder/file paths.
     * @return The file name, or null if this is an entity-only path
     */
    String getFileNameString() {
        return fileName
    }

    /**
     * Get the parent folder ID for folder/file paths.
     * For entity-only paths, returns the synId.
     */
    String getParentFolderId() {
        return synId
    }

    @Override
    FileSystem getFileSystem() {
        return fileSystem
    }

    @Override
    boolean isAbsolute() {
        return true
    }

    @Override
    Path getRoot() {
        return null
    }

    @Override
    Path getFileName() {
        // If this is a folder/file path, return just the file name part
        if (fileName != null) {
            return new SynapsePath(fileSystem, synId, null, fileName, fileName)
        }
        return this
    }

    @Override
    Path getParent() {
        // If this is a folder/file path, return the folder part
        if (fileName != null) {
            return new SynapsePath(fileSystem, synId, version)
        }
        return null
    }

    @Override
    int getNameCount() {
        return fileName != null ? 2 : 1
    }

    @Override
    Path getName(int index) {
        if (index != 0) {
            throw new IllegalArgumentException("Invalid name index: ${index}")
        }
        return this
    }

    @Override
    Path subpath(int beginIndex, int endIndex) {
        if (beginIndex != 0 || endIndex != 1) {
            throw new IllegalArgumentException("Invalid subpath range")
        }
        return this
    }

    @Override
    boolean startsWith(Path other) {
        return equals(other)
    }

    @Override
    boolean startsWith(String other) {
        return toString() == other
    }

    @Override
    boolean endsWith(Path other) {
        return equals(other)
    }

    @Override
    boolean endsWith(String other) {
        return toString() == other
    }

    @Override
    Path normalize() {
        return this
    }

    @Override
    Path resolve(Path other) {
        if (other == null) {
            return this
        }
        return resolve(other.toString())
    }

    @Override
    Path resolve(String other) {
        if (other == null || other.isEmpty()) {
            return this
        }
        // Allow building nested paths by appending to existing fileName
        String newFileName = fileName != null ? fileName + "/" + other : other
        def newUri = "syn://${synId}/${newFileName}"
        return new SynapsePath(fileSystem, synId, null, newFileName, newUri)
    }

    @Override
    Path resolveSibling(Path other) {
        throw new UnsupportedOperationException("Cannot resolve sibling paths for Synapse URIs")
    }

    @Override
    Path resolveSibling(String other) {
        throw new UnsupportedOperationException("Cannot resolve sibling paths for Synapse URIs")
    }

    @Override
    Path relativize(Path other) {
        throw new UnsupportedOperationException("Cannot relativize Synapse URIs")
    }

    @Override
    URI toUri() {
        return URI.create(originalUri)
    }

    @Override
    Path toAbsolutePath() {
        return this
    }

    @Override
    Path toRealPath(LinkOption... options) {
        return this
    }

    @Override
    File toFile() {
        throw new UnsupportedOperationException("Synapse paths cannot be converted to File")
    }

    @Override
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("Watch not supported for Synapse paths")
    }

    @Override
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) {
        throw new UnsupportedOperationException("Watch not supported for Synapse paths")
    }

    @Override
    Iterator<Path> iterator() {
        return Collections.singletonList((Path) this).iterator()
    }

    @Override
    int compareTo(Path other) {
        if (other instanceof SynapsePath) {
            def o = (SynapsePath) other
            def result = synId <=> o.synId
            if (result != 0) return result
            def versionResult = (version ?: 0) <=> (o.version ?: 0)
            if (versionResult != 0) return versionResult
            return (fileName ?: '') <=> (o.fileName ?: '')
        }
        return toString() <=> other.toString()
    }

    @Override
    boolean equals(Object obj) {
        if (this.is(obj)) return true
        if (!(obj instanceof SynapsePath)) return false
        SynapsePath other = (SynapsePath) obj
        return synId == other.synId &&
               version == other.version &&
               fileSystem.is(other.fileSystem)
    }

    @Override
    int hashCode() {
        return Objects.hash(synId, version)
    }

    @Override
    String toString() {
        return getActualFileName()
    }

    String toUriString() {
        return originalUri
    }
}
