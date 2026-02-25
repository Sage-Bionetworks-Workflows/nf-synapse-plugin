package nextflow.synapse

import groovy.transform.CompileStatic
import nextflow.SysEnv
import nextflow.secret.SecretsLoader

/**
 * Configuration for Synapse plugin.
 *
 * Example nextflow.config:
 * <pre>
 * synapse {
 *     // authToken defaults to secrets.SYNAPSE_AUTH_TOKEN — only set this
 *     // if you want to use a different secret name
 *     authToken = secrets.MY_OTHER_TOKEN
 *     endpoint = 'https://repo-prod.prod.sagebase.org'  // optional
 * }
 * </pre>
 */
@CompileStatic
class SynapseConfig {

    static final String DEFAULT_ENDPOINT = 'https://repo-prod.prod.sagebase.org'
    static final String DEFAULT_SECRET_NAME = 'SYNAPSE_AUTH_TOKEN'

    private String authToken
    private String endpoint

    SynapseConfig(Map config) {
        this.authToken = config.authToken as String
        this.endpoint = (config.endpoint as String) ?: DEFAULT_ENDPOINT
    }

    String getAuthToken() {
        if (authToken) {
            return authToken
        }
        def secretToken = loadSecret(DEFAULT_SECRET_NAME)
        if (secretToken) {
            return secretToken
        }
        def envToken = SysEnv.get(DEFAULT_SECRET_NAME)
        if (envToken) {
            return envToken
        }
        throw new IllegalStateException(
            "Synapse authentication token not configured. " +
            "Set it via: nextflow secrets set SYNAPSE_AUTH_TOKEN <your-token>"
        )
    }

    String getEndpoint() {
        return endpoint
    }

    boolean hasAuthToken() {
        return authToken || loadSecret(DEFAULT_SECRET_NAME) || SysEnv.get(DEFAULT_SECRET_NAME)
    }

    private static String loadSecret(String name) {
        try {
            return SecretsLoader.instance.load().getSecret(name)?.value
        } catch (Exception e) {
            return null
        }
    }
}
