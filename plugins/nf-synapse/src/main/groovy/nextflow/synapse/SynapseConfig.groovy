package nextflow.synapse

import groovy.transform.CompileStatic
import nextflow.SysEnv

/**
 * Configuration for Synapse plugin.
 *
 * Example nextflow.config:
 * <pre>
 * synapse {
 *     authToken = secrets.SYNAPSE_AUTH_TOKEN
 *     endpoint = 'https://repo-prod.prod.sagebase.org'  // optional
 * }
 * </pre>
 */
@CompileStatic
class SynapseConfig {

    static final String DEFAULT_ENDPOINT = 'https://repo-prod.prod.sagebase.org'

    private String authToken
    private String endpoint

    SynapseConfig(Map config) {
        this.authToken = config.authToken as String
        this.endpoint = (config.endpoint as String) ?: DEFAULT_ENDPOINT
    }

    String getAuthToken() {
        if (!authToken) {
            def envToken = SysEnv.get('SYNAPSE_AUTH_TOKEN')
            if (envToken) {
                return envToken
            }
            throw new IllegalStateException(
                "Synapse authentication token not configured. " +
                "Set it via: nextflow secrets set SYNAPSE_AUTH_TOKEN <your-token> " +
                "and configure: synapse { authToken = secrets.SYNAPSE_AUTH_TOKEN }"
            )
        }
        return authToken
    }

    String getEndpoint() {
        return endpoint
    }

    boolean hasAuthToken() {
        return authToken || SysEnv.get('SYNAPSE_AUTH_TOKEN')
    }
}
