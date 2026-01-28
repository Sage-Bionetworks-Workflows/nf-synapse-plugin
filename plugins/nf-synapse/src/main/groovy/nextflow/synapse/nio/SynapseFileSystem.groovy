package nextflow.synapse.nio

import groovy.transform.CompileStatic
import nextflow.synapse.SynapseConfig
import nextflow.synapse.client.SynapseClient

import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.UserPrincipalLookupService

/**
 * FileSystem implementation for Synapse.
 */
@CompileStatic
class SynapseFileSystem extends FileSystem {

    private final SynapseFileSystemProvider provider
    private final SynapseConfig config
    private final SynapseClient client
    private volatile boolean open = true

    SynapseFileSystem(SynapseFileSystemProvider provider, SynapseConfig config) {
        this.provider = provider
        this.config = config
        this.client = new SynapseClient(config)
    }

    SynapseClient getClient() {
        return client
    }

    SynapseConfig getConfig() {
        return config
    }

    @Override
    SynapseFileSystemProvider provider() {
        return provider
    }

    @Override
    void close() {
        open = false
    }

    @Override
    boolean isOpen() {
        return open
    }

    @Override
    boolean isReadOnly() {
        return false  // Write support enabled
    }

    @Override
    String getSeparator() {
        return "/"
    }

    @Override
    Iterable<Path> getRootDirectories() {
        return Collections.emptyList()
    }

    @Override
    Iterable<FileStore> getFileStores() {
        return Collections.emptyList()
    }

    @Override
    Set<String> supportedFileAttributeViews() {
        return Collections.singleton("basic")
    }

    @Override
    Path getPath(String first, String... more) {
        // Join all components
        if (more != null && more.length > 0) {
            def fullPath = first
            for (String component : more) {
                fullPath = fullPath + '/' + component
            }
            return new SynapsePath(this, fullPath)
        }
        return new SynapsePath(this, first)
    }

    @Override
    PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("Path matching not supported for Synapse")
    }

    @Override
    UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("User principal lookup not supported")
    }

    @Override
    WatchService newWatchService() {
        throw new UnsupportedOperationException("Watch service not supported for Synapse")
    }
}
