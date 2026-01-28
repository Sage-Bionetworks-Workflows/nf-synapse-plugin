package nextflow.synapse

import nextflow.synapse.client.SynapseClient
import nextflow.synapse.client.SynapseUploader
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class SynapseUploaderTest extends Specification {

    @TempDir
    Path tempDir

    def "should compute MD5 correctly"() {
        given:
        def client = Mock(SynapseClient)
        def uploader = new SynapseUploader(client)

        // Create a test file with known content
        def testFile = tempDir.resolve('test.txt')
        Files.write(testFile, 'Hello, World!'.bytes)

        when:
        def md5 = uploader.computeMd5(testFile)

        then:
        // MD5 of "Hello, World!" is 65a8e27d8879283831b664bd8b7f0ad4
        md5 == '65a8e27d8879283831b664bd8b7f0ad4'
    }

    def "should compute MD5 of byte array"() {
        given:
        def client = Mock(SynapseClient)
        def uploader = new SynapseUploader(client)

        when:
        def md5 = uploader.computeMd5('Hello, World!'.bytes)

        then:
        md5 == '65a8e27d8879283831b664bd8b7f0ad4'
    }

    def "should calculate correct part size for small files"() {
        given:
        def client = Mock(SynapseClient)
        def uploader = new SynapseUploader(client)

        when:
        def partSize = uploader.calculatePartSize(10 * 1024 * 1024)  // 10MB

        then:
        partSize == 5 * 1024 * 1024  // 5MB minimum
    }

    def "should calculate correct part size for large files"() {
        given:
        def client = Mock(SynapseClient)
        def uploader = new SynapseUploader(client)

        when:
        // File larger than 10000 * 5MB requires larger parts
        def partSize = uploader.calculatePartSize(100L * 1024 * 1024 * 1024)  // 100GB

        then:
        // Should increase part size to stay within 10000 parts limit
        partSize > 5 * 1024 * 1024
        100L * 1024 * 1024 * 1024 / partSize <= 10000
    }

    def "should detect content type from extension"() {
        given:
        def client = Mock(SynapseClient)
        def uploader = new SynapseUploader(client)

        expect:
        // Access private method via reflection for testing
        uploader.detectContentType('file.txt') == 'text/plain'
        uploader.detectContentType('file.csv') == 'text/csv'
        uploader.detectContentType('file.json') == 'application/json'
        uploader.detectContentType('file.gz') == 'application/gzip'
        uploader.detectContentType('file.unknown') == 'application/octet-stream'
    }

    def "should throw error when parent is not a folder"() {
        given:
        def client = Mock(SynapseClient)
        def uploader = new SynapseUploader(client)

        def testFile = tempDir.resolve('test.txt')
        Files.write(testFile, 'content'.bytes)

        client.isFolder('syn1234567') >> false

        when:
        uploader.uploadFile(testFile, 'syn1234567', 'test.txt')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('not a Folder')
    }

    def "should handle already completed upload (deduplication)"() {
        given:
        def client = Mock(SynapseClient)
        def uploader = new SynapseUploader(client)

        def testFile = tempDir.resolve('test.txt')
        Files.write(testFile, 'content'.bytes)

        client.isFolder('syn1234567') >> true
        client.startMultipartUpload(_, _, _, _, _) >> [
            uploadId: 'upload-123',
            state: 'COMPLETED',
            resultFileHandleId: 'fh-456'
        ]
        client.createFileEntity('syn1234567', 'test.txt', 'fh-456') >> [
            id: 'syn9876543'
        ]

        when:
        def result = uploader.uploadFile(testFile, 'syn1234567', 'test.txt')

        then:
        result == 'syn9876543'
        // Should not call presigned URL methods since upload was already complete
        0 * client.getPresignedUploadUrls(_, _)
    }
}
