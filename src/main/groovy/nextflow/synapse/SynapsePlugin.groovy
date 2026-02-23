package nextflow.synapse

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

/**
 * Nextflow plugin for accessing Synapse (Sage Bionetworks) files
 * using syn:// URIs in pipelines and samplesheets.
 */
@CompileStatic
class SynapsePlugin extends BasePlugin {

    SynapsePlugin(PluginWrapper wrapper) {
        super(wrapper)
    }

    @Override
    void start() {
        super.start()
    }

    @Override
    void stop() {
        super.stop()
    }
}
