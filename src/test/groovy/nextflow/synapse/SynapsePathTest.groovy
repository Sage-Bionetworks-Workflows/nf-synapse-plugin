package nextflow.synapse

import nextflow.synapse.nio.SynapseFileSystem
import nextflow.synapse.nio.SynapseFileSystemProvider
import nextflow.synapse.nio.SynapsePath
import spock.lang.Specification
import spock.lang.Unroll

class SynapsePathTest extends Specification {

    SynapseFileSystem fileSystem

    def setup() {
        def provider = new SynapseFileSystemProvider()
        def config = new SynapseConfig([authToken: 'test-token'])
        fileSystem = new SynapseFileSystem(provider, config)
    }

    @Unroll
    def "should parse valid URI: #uri"() {
        when:
        def path = new SynapsePath(fileSystem, uri)

        then:
        path.synId == expectedSynId
        path.version == expectedVersion

        where:
        uri                    | expectedSynId  | expectedVersion
        'syn://syn1234567'     | 'syn1234567'   | null
        'syn://syn1234567.5'   | 'syn1234567'   | 5
        'syn://syn1234567.1'   | 'syn1234567'   | 1
        'syn://syn99999999.99' | 'syn99999999'  | 99
        'syn1234567'           | 'syn1234567'   | null
        'syn1234567.5'         | 'syn1234567'   | 5
    }

    @Unroll
    def "should parse folder/file URI: #uri"() {
        when:
        def path = new SynapsePath(fileSystem, uri)

        then:
        path.synId == expectedSynId
        path.fileNameString == expectedFileName
        path.isFolderFilePath() == true
        path.version == null

        where:
        uri                                | expectedSynId  | expectedFileName
        'syn://syn1234567/results.txt'     | 'syn1234567'   | 'results.txt'
        'syn://syn1234567/path/to/file.gz' | 'syn1234567'   | 'path/to/file.gz'
        'syn1234567/output.csv'            | 'syn1234567'   | 'output.csv'
    }

    @Unroll
    def "should reject invalid URI: #uri"() {
        when:
        new SynapsePath(fileSystem, uri)

        then:
        thrown(IllegalArgumentException)

        where:
        uri << [
            'syn://invalid',
            'syn://syn',
            'syn://synABC',
            's3://bucket/key',
            'syn://syn1234567.abc',
            'syn://syn1234567.1.2'
        ]
    }

    def "should return correct URI"() {
        when:
        def path = new SynapsePath(fileSystem, 'syn://syn1234567.5')

        then:
        path.toUri() == URI.create('syn://syn1234567.5')
        path.toUriString() == 'syn://syn1234567.5'
    }

    def "should be absolute"() {
        when:
        def path = new SynapsePath(fileSystem, 'syn://syn1234567')

        then:
        path.isAbsolute()
    }

    def "should compare paths correctly"() {
        given:
        def path1 = new SynapsePath(fileSystem, 'syn://syn1234567')
        def path2 = new SynapsePath(fileSystem, 'syn://syn1234567')
        def path3 = new SynapsePath(fileSystem, 'syn://syn1234567.5')
        def path4 = new SynapsePath(fileSystem, 'syn://syn9999999')

        expect:
        // Verify fileSystem is the same instance
        path1.fileSystem.is(path2.fileSystem)
        // Verify basic properties match
        path1.synId == path2.synId
        path1.version == path2.version
        path1.fileNameString == path2.fileNameString
        // Now check equality
        path1.equals(path2)
        path1 != path3
        path1 != path4
        path1.compareTo(path3) < 0  // no version < version 5
        path1.compareTo(path4) < 0  // 1234567 < 9999999
    }

    def "should report hasVersion correctly"() {
        expect:
        !new SynapsePath(fileSystem, 'syn://syn1234567').hasVersion()
        new SynapsePath(fileSystem, 'syn://syn1234567.5').hasVersion()
    }

    def "should throw on unsupported operations"() {
        given:
        def path = new SynapsePath(fileSystem, 'syn://syn1234567')

        when:
        path.toFile()

        then:
        thrown(UnsupportedOperationException)
    }

    def "should resolve filename against folder path"() {
        given:
        def folderPath = new SynapsePath(fileSystem, 'syn://syn1234567')

        when:
        def filePath = folderPath.resolve('results.txt')

        then:
        filePath.synId == 'syn1234567'
        filePath.fileNameString == 'results.txt'
        filePath.isFolderFilePath() == true
        filePath.toUriString() == 'syn://syn1234567/results.txt'
    }

    def "should allow nested resolve for subfolder paths"() {
        given:
        def folderPath = new SynapsePath(fileSystem, 'syn://syn1234567')

        when:
        def subPath = folderPath.resolve('subdir')
        def nestedPath = subPath.resolve('results.txt')

        then:
        subPath.fileNameString == 'subdir'
        nestedPath.fileNameString == 'subdir/results.txt'
        nestedPath.toUriString() == 'syn://syn1234567/subdir/results.txt'
    }

    def "should return correct parent for folder/file path"() {
        given:
        def filePath = new SynapsePath(fileSystem, 'syn://syn1234567/results.txt')

        when:
        def parent = filePath.parent

        then:
        parent != null
        parent.synId == 'syn1234567'
        !parent.isFolderFilePath()
    }

    def "should return null parent for entity-only path"() {
        given:
        def entityPath = new SynapsePath(fileSystem, 'syn://syn1234567')

        expect:
        entityPath.parent == null
    }

    def "should return correct nameCount for paths"() {
        expect:
        new SynapsePath(fileSystem, 'syn://syn1234567').nameCount == 1
        new SynapsePath(fileSystem, 'syn://syn1234567/file.txt').nameCount == 2
    }

    def "toString should return actual filename from Synapse (falls back to synId if unavailable)"() {
        expect:
        // Without a real Synapse connection, it falls back to synId
        new SynapsePath(fileSystem, 'syn://syn1234567').toString() == 'syn1234567'
    }
}
