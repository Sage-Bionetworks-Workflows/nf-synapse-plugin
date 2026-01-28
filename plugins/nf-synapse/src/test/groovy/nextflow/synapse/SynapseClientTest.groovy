package nextflow.synapse

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import nextflow.synapse.client.SynapseClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

import static com.github.tomakehurst.wiremock.client.WireMock.*

class SynapseClientTest extends Specification {

    @Shared
    @AutoCleanup('stop')
    WireMockServer wireMock = new WireMockServer(0)

    SynapseClient client

    def setupSpec() {
        wireMock.start()
        WireMock.configureFor('localhost', wireMock.port())
    }

    def setup() {
        def config = new SynapseConfig([
            authToken: 'test-token',
            endpoint: "http://localhost:${wireMock.port()}"
        ])
        client = new SynapseClient(config)
        wireMock.resetAll()
    }

    def "should fetch entity metadata"() {
        given:
        stubFor(get(urlEqualTo('/repo/v1/entity/syn1234567'))
            .withHeader('Authorization', equalTo('Bearer test-token'))
            .willReturn(okJson('''
                {
                    "id": "syn1234567",
                    "name": "test-file.txt",
                    "concreteType": "org.sagebionetworks.repo.model.FileEntity",
                    "dataFileHandleId": "12345",
                    "fileSize": 1024
                }
            ''')))

        when:
        def entity = client.getEntity('syn1234567')

        then:
        entity.id == 'syn1234567'
        entity.name == 'test-file.txt'
        entity.dataFileHandleId == '12345'
        entity.fileSize == 1024
    }

    def "should fetch versioned entity"() {
        given:
        stubFor(get(urlEqualTo('/repo/v1/entity/syn1234567/version/5'))
            .willReturn(okJson('''
                {
                    "id": "syn1234567",
                    "versionNumber": 5,
                    "concreteType": "org.sagebionetworks.repo.model.FileEntity"
                }
            ''')))

        when:
        def entity = client.getEntity('syn1234567', 5)

        then:
        entity.id == 'syn1234567'
        entity.versionNumber == 5
    }

    def "should get presigned URL"() {
        given:
        stubFor(get(urlPathEqualTo('/file/v1/file/12345'))
            .withQueryParam('redirect', equalTo('false'))
            .withQueryParam('fileAssociateType', equalTo('FileEntity'))
            .withQueryParam('fileAssociateId', equalTo('syn1234567'))
            .willReturn(okJson('''
                {
                    "preSignedURL": "https://s3.amazonaws.com/bucket/key?signature=xxx"
                }
            ''')))

        when:
        def url = client.getPresignedUrl('syn1234567', '12345')

        then:
        url == 'https://s3.amazonaws.com/bucket/key?signature=xxx'
    }

    def "should throw SecurityException on 401"() {
        given:
        stubFor(get(urlEqualTo('/repo/v1/entity/syn1234567'))
            .willReturn(unauthorized()))

        when:
        client.getEntity('syn1234567')

        then:
        def e = thrown(SecurityException)
        e.message.contains('authentication failed')
    }

    def "should throw AccessDeniedException on 403"() {
        given:
        stubFor(get(urlEqualTo('/repo/v1/entity/syn1234567'))
            .willReturn(forbidden()))

        when:
        client.getEntity('syn1234567')

        then:
        thrown(AccessDeniedException)
    }

    def "should throw NoSuchFileException on 404"() {
        given:
        stubFor(get(urlEqualTo('/repo/v1/entity/syn1234567'))
            .willReturn(notFound()))

        when:
        client.getEntity('syn1234567')

        then:
        thrown(NoSuchFileException)
    }

    def "should check if entity is a folder"() {
        given:
        stubFor(get(urlEqualTo('/repo/v1/entity/syn1234567'))
            .willReturn(okJson('''
                {
                    "id": "syn1234567",
                    "name": "my-folder",
                    "concreteType": "org.sagebionetworks.repo.model.Folder"
                }
            ''')))

        when:
        def isFolder = client.isFolder('syn1234567')

        then:
        isFolder == true
    }

    def "should return false for non-folder entity"() {
        given:
        stubFor(get(urlEqualTo('/repo/v1/entity/syn1234567'))
            .willReturn(okJson('''
                {
                    "id": "syn1234567",
                    "name": "test-file.txt",
                    "concreteType": "org.sagebionetworks.repo.model.FileEntity"
                }
            ''')))

        when:
        def isFolder = client.isFolder('syn1234567')

        then:
        isFolder == false
    }

    def "should start multipart upload"() {
        given:
        stubFor(post(urlEqualTo('/file/v1/file/multipart'))
            .withHeader('Authorization', equalTo('Bearer test-token'))
            .withHeader('Content-Type', equalTo('application/json'))
            .willReturn(okJson('''
                {
                    "uploadId": "upload-123",
                    "state": "UPLOADING",
                    "partsState": "0"
                }
            ''')))

        when:
        def result = client.startMultipartUpload('test.txt', 1024, 'abc123', 'text/plain', 5242880)

        then:
        result.uploadId == 'upload-123'
        result.state == 'UPLOADING'
    }

    def "should get presigned upload URLs"() {
        given:
        stubFor(post(urlEqualTo('/file/v1/file/multipart/upload-123/presigned/url/batch'))
            .withHeader('Authorization', equalTo('Bearer test-token'))
            .willReturn(okJson('''
                {
                    "partPresignedUrls": [
                        {
                            "partNumber": 1,
                            "uploadPresignedUrl": "https://s3.amazonaws.com/bucket/key?sig=xxx",
                            "signedHeaders": {"x-amz-header": "value"}
                        }
                    ]
                }
            ''')))

        when:
        def result = client.getPresignedUploadUrls('upload-123', [1])

        then:
        result.partPresignedUrls.size() == 1
        result.partPresignedUrls[0].partNumber == 1
        result.partPresignedUrls[0].uploadPresignedUrl == 'https://s3.amazonaws.com/bucket/key?sig=xxx'
    }

    def "should add upload part"() {
        given:
        stubFor(put(urlEqualTo('/file/v1/file/multipart/upload-123/add/1?partMD5Hex=abc123'))
            .withHeader('Authorization', equalTo('Bearer test-token'))
            .willReturn(okJson('''
                {
                    "addPartState": "ADD_SUCCESS"
                }
            ''')))

        when:
        def result = client.addUploadPart('upload-123', 1, 'abc123')

        then:
        result.addPartState == 'ADD_SUCCESS'
    }

    def "should complete multipart upload"() {
        given:
        stubFor(put(urlEqualTo('/file/v1/file/multipart/upload-123/complete'))
            .withHeader('Authorization', equalTo('Bearer test-token'))
            .willReturn(okJson('''
                {
                    "state": "COMPLETED",
                    "resultFileHandleId": "fh-456"
                }
            ''')))

        when:
        def result = client.completeMultipartUpload('upload-123')

        then:
        result.state == 'COMPLETED'
        result.resultFileHandleId == 'fh-456'
    }

    def "should create file entity"() {
        given:
        stubFor(post(urlEqualTo('/repo/v1/entity'))
            .withHeader('Authorization', equalTo('Bearer test-token'))
            .withHeader('Content-Type', equalTo('application/json'))
            .willReturn(okJson('''
                {
                    "id": "syn9876543",
                    "name": "uploaded-file.txt",
                    "parentId": "syn1234567",
                    "concreteType": "org.sagebionetworks.repo.model.FileEntity",
                    "dataFileHandleId": "fh-456"
                }
            ''')))

        when:
        def result = client.createFileEntity('syn1234567', 'uploaded-file.txt', 'fh-456')

        then:
        result.id == 'syn9876543'
        result.name == 'uploaded-file.txt'
        result.parentId == 'syn1234567'
    }
}
