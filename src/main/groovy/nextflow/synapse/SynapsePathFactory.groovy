package nextflow.synapse

import groovy.transform.CompileStatic
import nextflow.Global
import nextflow.Session
import nextflow.file.FileSystemPathFactory
import nextflow.synapse.nio.SynapseFileSystem
import nextflow.synapse.nio.SynapseFileSystemProvider
import nextflow.synapse.nio.SynapsePath
import org.pf4j.Extension

import java.nio.file.Path

/**
 * Factory for creating SynapsePath instances from syn:// URIs.
 */
@Extension
@CompileStatic
class SynapsePathFactory extends FileSystemPathFactory {

    private SynapseConfig config

    @Override
    protected Path parseUri(String uri) {
        if (!uri.startsWith('syn://')) {
            return null
        }

        def provider = getSynapseProvider()
        def fs = provider.getFileSystem(URI.create(uri)) ?:
                 provider.newFileSystem(URI.create(uri), getConfig())

        return fs.getPath(uri)
    }

    @Override
    protected String toUriString(Path path) {
        if (path instanceof SynapsePath) {
            return path.toUri().toString()
        }
        return null
    }

    @Override
    protected String getBashLib(Path path) {
        return null
    }

    @Override
    protected String getUploadCmd(String source, Path target) {
        return null
    }

    private SynapseFileSystemProvider getSynapseProvider() {
        return SynapseFileSystemProvider.instance()
    }

    private Map getConfig() {
        def session = Global.session as Session
        def synapseConfig = session?.config?.navigate('synapse') as Map ?: [:]
        return [config: new SynapseConfig(synapseConfig)]
    }
}
