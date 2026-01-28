package nextflow.synapse.client

import groovy.transform.CompileStatic
import nextflow.synapse.SynapseConfig

/**
 * Manages Synapse authentication tokens.
 */
@CompileStatic
class SynapseAuthManager {

    private final SynapseConfig config

    SynapseAuthManager(SynapseConfig config) {
        this.config = config
    }

    String getAuthToken() {
        return config.getAuthToken()
    }

    String getAuthorizationHeader() {
        return "Bearer ${getAuthToken()}"
    }

    boolean isConfigured() {
        return config.hasAuthToken()
    }
}
