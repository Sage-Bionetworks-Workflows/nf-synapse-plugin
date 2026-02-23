package nextflow.synapse

import spock.lang.Specification

class SynapseConfigTest extends Specification {

    def "should use default endpoint when not specified"() {
        when:
        def config = new SynapseConfig([authToken: 'test-token'])

        then:
        config.endpoint == 'https://repo-prod.prod.sagebase.org'
    }

    def "should use custom endpoint when specified"() {
        when:
        def config = new SynapseConfig([
            authToken: 'test-token',
            endpoint: 'https://custom.synapse.org'
        ])

        then:
        config.endpoint == 'https://custom.synapse.org'
    }

    def "should return auth token when configured"() {
        when:
        def config = new SynapseConfig([authToken: 'my-secret-token'])

        then:
        config.authToken == 'my-secret-token'
    }

    def "should throw when auth token not configured"() {
        given:
        def config = new SynapseConfig([:])

        when:
        config.authToken

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Synapse authentication token not configured')
    }

    def "should report hasAuthToken correctly"() {
        expect:
        new SynapseConfig([authToken: 'token']).hasAuthToken()
        !new SynapseConfig([:]).hasAuthToken()
    }
}
