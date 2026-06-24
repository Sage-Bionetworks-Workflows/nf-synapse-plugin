package nextflow.synapse

import groovy.transform.CompileStatic
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

/**
 * Declares the {@code synapse} config scope so Nextflow's config validation
 * recognizes {@code synapse.*} options instead of warning
 * "Unrecognized config option 'synapse.authToken'".
 *
 * This class only describes the schema — the values are read and used by
 * {@link SynapseConfig}.
 */
@ScopeName('synapse')
@Description('''
    The `synapse` scope configures access to Synapse (Sage Bionetworks) files via `syn://` URIs.
''')
@CompileStatic
class SynapseConfigScope implements ConfigScope {

    @ConfigOption
    @Description('''
        Synapse personal access token. Defaults to the `SYNAPSE_AUTH_TOKEN` secret;
        set this only if you want to use a different secret name.
    ''')
    String authToken

    @ConfigOption
    @Description('''
        Synapse REST API endpoint (default: `https://repo-prod.prod.sagebase.org`).
    ''')
    String endpoint
}
