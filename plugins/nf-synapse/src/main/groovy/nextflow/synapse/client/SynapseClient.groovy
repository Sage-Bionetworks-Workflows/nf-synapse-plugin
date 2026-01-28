package nextflow.synapse.client

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.synapse.SynapseConfig

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.time.Duration

/**
 * REST client for Synapse API.
 */
@Slf4j
@CompileStatic
class SynapseClient {

    private final SynapseConfig config
    private final SynapseAuthManager authManager
    private final HttpClient httpClient
    private final HttpClient noRedirectClient
    private final JsonSlurper jsonSlurper

    SynapseClient(SynapseConfig config) {
        this.config = config
        this.authManager = new SynapseAuthManager(config)
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        this.noRedirectClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        this.jsonSlurper = new JsonSlurper()
    }

    /**
     * Get entity metadata from Synapse.
     * @param synId The Synapse ID (e.g., "syn1234567")
     * @param version Optional version number
     * @return Entity metadata map
     */
    Map getEntity(String synId, Integer version = null) {
        def url = "${config.endpoint}/repo/v1/entity/${synId}"
        if (version != null) {
            url += "/version/${version}"
        }

        def response = executeGet(url)
        return parseJson(response)
    }

    /**
     * Get presigned download URL for a file.
     * @param synId The Synapse ID
     * @param fileHandleId The file handle ID from entity metadata
     * @return Presigned URL for downloading
     */
    String getPresignedUrl(String synId, String fileHandleId) {
        def url = "${config.endpoint}/file/v1/file/${fileHandleId}" +
                  "?redirect=false&fileAssociateType=FileEntity&fileAssociateId=${synId}"

        log.debug("GET (no redirect) {}", url)

        def request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authManager.getAuthorizationHeader())
            .GET()
            .timeout(Duration.ofSeconds(60))
            .build()

        def response = noRedirectClient.send(request, HttpResponse.BodyHandlers.ofString())

        // Handle redirect response - extract Location header
        if (response.statusCode() == 307 || response.statusCode() == 302 || response.statusCode() == 301) {
            def location = response.headers().firstValue("Location")
            if (location.isPresent()) {
                return location.get()
            }
            throw new IOException("Redirect response without Location header")
        }

        handleResponseStatus(response, url)

        // Synapse returns the presigned URL as plain text
        def body = response.body()?.trim()
        if (body?.startsWith('http')) {
            return body
        }

        // Try JSON format as fallback
        def json = parseJson(body)
        return json.preSignedURL as String
    }

    /**
     * Get file handle metadata.
     * @param fileHandleId The file handle ID
     * @return File handle metadata
     */
    Map getFileHandle(String synId, String fileHandleId) {
        def url = "${config.endpoint}/repo/v1/entity/${synId}/filehandles"

        def response = executeGet(url)
        def json = parseJson(response)
        def handles = json.list as List<Map>
        return handles?.find { it.id == fileHandleId } as Map
    }

    /**
     * Check if an entity is a Folder.
     * @param synId The Synapse ID
     * @return true if the entity is a Folder, false otherwise
     */
    boolean isFolder(String synId) {
        def entity = getEntity(synId)
        def concreteType = entity.concreteType as String
        return concreteType?.endsWith('.Folder')
    }

    /**
     * Create a Folder entity in Synapse.
     * @param parentId The parent folder ID
     * @param folderName Name of the folder to create
     * @return Map containing the created folder metadata (including id)
     */
    Map createFolder(String parentId, String folderName) {
        def url = "${config.endpoint}/repo/v1/entity"
        def requestBody = [
            concreteType: 'org.sagebionetworks.repo.model.Folder',
            name: folderName,
            parentId: parentId
        ]
        def response = executePost(url, requestBody)
        return parseJson(response)
    }

    /**
     * List children of a folder to check if a subfolder exists.
     * @param parentId The parent folder ID
     * @return List of child entity metadata
     */
    List<Map> listChildren(String parentId) {
        def url = "${config.endpoint}/repo/v1/entity/children"
        def requestBody = [
            parentId: parentId,
            includeTypes: ['folder', 'file']
        ]
        def response = executePost(url, requestBody)
        def json = parseJson(response)
        return json.page as List<Map>
    }

    /**
     * Start a multipart upload.
     * @param fileName Name of the file to upload
     * @param fileSize Size of the file in bytes
     * @param md5Hex MD5 checksum of the file as hex string
     * @param contentType MIME type of the file (optional, defaults to application/octet-stream)
     * @param partSizeBytes Size of each part (optional, defaults to 5MB minimum)
     * @return Map containing uploadId and other upload metadata
     */
    Map startMultipartUpload(String fileName, long fileSize, String md5Hex, String contentType = 'application/octet-stream', long partSizeBytes = 5 * 1024 * 1024) {
        def url = "${config.endpoint}/file/v1/file/multipart"

        def requestBody = [
            concreteType: 'org.sagebionetworks.repo.model.file.MultipartUploadRequest',
            fileName: fileName,
            fileSizeBytes: fileSize,
            contentMD5Hex: md5Hex,
            contentType: contentType,
            partSizeBytes: partSizeBytes
        ]

        def response = executePost(url, requestBody)
        return parseJson(response)
    }

    /**
     * Get presigned URLs for uploading parts.
     * @param uploadId The upload ID from startMultipartUpload
     * @param partNumbers List of part numbers to get URLs for (1-indexed)
     * @return Map containing list of presigned URLs and headers
     */
    Map getPresignedUploadUrls(String uploadId, List<Integer> partNumbers) {
        def url = "${config.endpoint}/file/v1/file/multipart/${uploadId}/presigned/url/batch"

        def requestBody = [
            partNumbers: partNumbers
        ]

        def response = executePost(url, requestBody)
        return parseJson(response)
    }

    /**
     * Confirm that a part was successfully uploaded.
     * @param uploadId The upload ID
     * @param partNumber The part number that was uploaded (1-indexed)
     * @param partMd5Hex MD5 checksum of the part as hex string
     * @return Map containing the add part result
     */
    Map addUploadPart(String uploadId, int partNumber, String partMd5Hex) {
        def url = "${config.endpoint}/file/v1/file/multipart/${uploadId}/add/${partNumber}?partMD5Hex=${partMd5Hex}"

        def response = executePut(url, null)
        return parseJson(response)
    }

    /**
     * Complete a multipart upload and get the resulting FileHandle.
     * @param uploadId The upload ID
     * @return Map containing the FileHandle metadata including id
     */
    Map completeMultipartUpload(String uploadId) {
        def url = "${config.endpoint}/file/v1/file/multipart/${uploadId}/complete"

        def response = executePut(url, null)
        return parseJson(response)
    }

    /**
     * Create a FileEntity in Synapse.
     * @param parentId The parent folder ID
     * @param fileName Name of the file
     * @param fileHandleId The FileHandle ID from completed upload
     * @return Map containing the created entity metadata
     */
    Map createFileEntity(String parentId, String fileName, String fileHandleId) {
        def url = "${config.endpoint}/repo/v1/entity"

        def requestBody = [
            concreteType: 'org.sagebionetworks.repo.model.FileEntity',
            name: fileName,
            parentId: parentId,
            dataFileHandleId: fileHandleId
        ]

        def response = executePost(url, requestBody)
        return parseJson(response)
    }

    private String executeGet(String url) {
        log.debug("GET {}", url)

        def request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authManager.getAuthorizationHeader())
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(60))
            .build()

        def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        handleResponseStatus(response, url)

        return response.body()
    }

    private String executePost(String url, Map body) {
        log.debug("POST {}", url)

        def jsonBody = body != null ? JsonOutput.toJson(body) : ''
        def request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authManager.getAuthorizationHeader())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(60))
            .build()

        def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        handleResponseStatus(response, url)

        return response.body()
    }

    private String executePut(String url, Map body) {
        log.debug("PUT {}", url)

        def jsonBody = body != null ? JsonOutput.toJson(body) : ''
        def request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authManager.getAuthorizationHeader())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(60))
            .build()

        def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        handleResponseStatus(response, url)

        return response.body()
    }

    private void handleResponseStatus(HttpResponse<String> response, String url) {
        switch (response.statusCode()) {
            case 200:
            case 201:
                return
            case 401:
                throw new SecurityException(
                    "Synapse authentication failed. Your token may be invalid or expired. " +
                    "Please update: nextflow secrets set SYNAPSE_AUTH_TOKEN <new-token>"
                )
            case 403:
                throw new AccessDeniedException(url,
                    null,
                    "Access denied. You don't have permission to access this Synapse entity."
                )
            case 404:
                throw new NoSuchFileException(url,
                    null,
                    "Synapse entity not found. The entity may not exist or you may not have access."
                )
            default:
                throw new IOException(
                    "Synapse API error (HTTP ${response.statusCode()}): ${response.body()}"
                )
        }
    }

    private Map parseJson(String json) {
        return jsonSlurper.parseText(json) as Map
    }
}
