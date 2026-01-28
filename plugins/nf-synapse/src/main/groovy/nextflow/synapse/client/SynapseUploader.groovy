package nextflow.synapse.client

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Utility class for uploading files to Synapse using multipart upload.
 *
 * Follows the synapseclient Python approach - streams from disk, never buffers entire file.
 */
@Slf4j
@CompileStatic
class SynapseUploader {

    private static final int MD5_CHUNK_SIZE = 2 * 1024 * 1024  // 2MB for MD5 streaming
    private static final long MIN_PART_SIZE = 5 * 1024 * 1024   // 5MB minimum part size
    private static final long MAX_PART_SIZE = 5L * 1024 * 1024 * 1024  // 5GB max part size
    private static final int MAX_PARTS = 10000  // S3 limit

    private final SynapseClient client
    private final HttpClient httpClient

    SynapseUploader(SynapseClient client) {
        this.client = client
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * Compute MD5 by streaming through file in chunks (no full buffering).
     * @param file Path to the file
     * @return MD5 hash as hex string
     */
    String computeMd5(Path file) {
        def md = MessageDigest.getInstance("MD5")
        def buffer = new byte[MD5_CHUNK_SIZE]
        file.newInputStream().withStream { InputStream input ->
            int bytesRead
            while ((bytesRead = input.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().encodeHex().toString()
    }

    /**
     * Compute MD5 of a byte array.
     * @param data The data to hash
     * @return MD5 hash as hex string
     */
    String computeMd5(byte[] data) {
        def md = MessageDigest.getInstance("MD5")
        md.update(data)
        return md.digest().encodeHex().toString()
    }

    /**
     * Calculate optimal part size for the given file size.
     * @param fileSize Size of the file in bytes
     * @return Part size in bytes
     */
    long calculatePartSize(long fileSize) {
        // Start with minimum part size
        long partSize = MIN_PART_SIZE

        // Increase part size if needed to stay within MAX_PARTS limit
        while (fileSize / partSize > MAX_PARTS) {
            partSize *= 2
            if (partSize > MAX_PART_SIZE) {
                throw new IllegalArgumentException(
                    "File size ${fileSize} is too large. Maximum supported file size is ${MAX_PARTS * MAX_PART_SIZE} bytes."
                )
            }
        }

        return partSize
    }

    /**
     * Ensure all folders in a path exist, creating them if necessary.
     * @param baseFolderId The root Synapse folder ID
     * @param relativePath Path like "results/qc" (without filename)
     * @return The Synapse ID of the deepest folder
     */
    String ensureFolderPath(String baseFolderId, String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return baseFolderId
        }

        def pathComponents = relativePath.split('/')
        def currentParentId = baseFolderId

        for (String folderName : pathComponents) {
            // Check if folder exists
            def existingFolder = findChildFolder(currentParentId, folderName)
            if (existingFolder != null) {
                currentParentId = existingFolder.id as String
            } else {
                // Create the folder
                def newFolder = client.createFolder(currentParentId, folderName)
                currentParentId = newFolder.id as String
                log.info("Created folder '{}' ({}) under {}", folderName, currentParentId, baseFolderId)
            }
        }

        return currentParentId
    }

    private Map findChildFolder(String parentId, String folderName) {
        def children = client.listChildren(parentId)
        return children?.find { it.name == folderName && it.type == 'org.sagebionetworks.repo.model.Folder' }
    }

    /**
     * Upload a file to a Synapse folder.
     * @param sourceFile Path to the local file to upload
     * @param parentFolderId Synapse ID of the parent folder
     * @param fileName Name for the file in Synapse (defaults to source file name)
     * @return Synapse ID of the created FileEntity
     */
    String uploadFile(Path sourceFile, String parentFolderId, String fileName = null) {
        if (fileName == null) {
            fileName = sourceFile.fileName.toString()
        }

        // Handle nested paths - split into folder path and actual filename
        String actualFileName = fileName
        String folderPath = null

        int lastSlash = fileName.lastIndexOf('/')
        if (lastSlash > 0) {
            folderPath = fileName.substring(0, lastSlash)
            actualFileName = fileName.substring(lastSlash + 1)
        }

        log.info("Uploading file '{}' to Synapse folder {}", actualFileName, parentFolderId)

        // Verify parent is a folder
        if (!client.isFolder(parentFolderId)) {
            throw new IllegalArgumentException("Cannot write to ${parentFolderId}: not a Folder")
        }

        // Ensure folder structure exists
        String targetFolderId = parentFolderId
        if (folderPath != null) {
            targetFolderId = ensureFolderPath(parentFolderId, folderPath)
        }

        // Use actualFileName and targetFolderId from here on
        fileName = actualFileName
        parentFolderId = targetFolderId

        def fileSize = Files.size(sourceFile)
        def md5Hex = computeMd5(sourceFile)
        def partSize = calculatePartSize(fileSize)

        log.debug("File size: {} bytes, MD5: {}, part size: {} bytes", fileSize, md5Hex, partSize)

        // Step 1: Start multipart upload
        def upload = client.startMultipartUpload(fileName, fileSize, md5Hex, detectContentType(fileName), partSize)
        def uploadId = upload.uploadId as String
        def uploadStatus = upload.state as String

        log.debug("Started multipart upload: {} (status: {})", uploadId, uploadStatus)

        // Check if upload is already complete (Synapse deduplication)
        if (uploadStatus == 'COMPLETED') {
            log.info("Upload already completed (file exists in Synapse)")
            def fileHandleId = upload.resultFileHandleId as String
            def entity = client.createFileEntity(parentFolderId, fileName, fileHandleId)
            return entity.id as String
        }

        // Step 2: Upload each part (read from disk on-demand)
        def numParts = (int) Math.ceil((double) fileSize / partSize)
        log.debug("Uploading {} parts", numParts)

        for (int partNum = 1; partNum <= numParts; partNum++) {
            uploadPart(sourceFile, uploadId, partNum, partSize, fileSize)
        }

        // Step 3: Complete upload -> FileHandle
        def fileHandle = client.completeMultipartUpload(uploadId)
        def fileHandleId = fileHandle.resultFileHandleId as String

        log.debug("Upload complete, file handle: {}", fileHandleId)

        // Step 4: Create FileEntity
        def entity = client.createFileEntity(parentFolderId, fileName, fileHandleId)
        def entityId = entity.id as String

        log.info("Created FileEntity {} in folder {}", entityId, parentFolderId)

        return entityId
    }

    /**
     * Upload a single part.
     */
    private void uploadPart(Path sourceFile, String uploadId, int partNumber, long partSize, long fileSize) {
        log.debug("Uploading part {}", partNumber)

        // Get presigned URL for this part
        def urlResponse = client.getPresignedUploadUrls(uploadId, [partNumber])
        def partPresignedUrls = urlResponse.partPresignedUrls as List<Map>
        if (partPresignedUrls == null || partPresignedUrls.isEmpty()) {
            throw new IOException("Failed to get presigned URL for part ${partNumber}")
        }

        def partInfo = partPresignedUrls[0]
        def presignedUrl = partInfo.uploadPresignedUrl as String
        def signedHeaders = partInfo.signedHeaders as Map<String, String>

        // Read the part data from disk
        def partData = readFilePart(sourceFile, partNumber, partSize, fileSize)
        def partMd5Hex = computeMd5(partData)

        // Upload to S3
        uploadPartToS3(presignedUrl, partData, signedHeaders)

        // Confirm part upload
        client.addUploadPart(uploadId, partNumber, partMd5Hex)

        log.debug("Part {} uploaded successfully ({} bytes)", partNumber, partData.length)
    }

    /**
     * Read a specific part from the file (seek and read only that chunk).
     */
    private byte[] readFilePart(Path file, int partNumber, long partSize, long fileSize) {
        def offset = (partNumber - 1) * partSize
        def remainingBytes = fileSize - offset
        def bytesToRead = (int) Math.min(partSize, remainingBytes)

        def buffer = new byte[bytesToRead]
        def raf = new RandomAccessFile(file.toFile(), "r")
        try {
            raf.seek(offset)
            raf.readFully(buffer)
        } finally {
            raf.close()
        }

        return buffer
    }

    /**
     * Upload part data to S3 using presigned URL.
     */
    private void uploadPartToS3(String presignedUrl, byte[] data, Map<String, String> signedHeaders) {
        def requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(presignedUrl))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
            .timeout(Duration.ofMinutes(10))

        // Add any signed headers from Synapse
        if (signedHeaders != null) {
            signedHeaders.each { String key, String value ->
                requestBuilder.header(key, value)
            }
        }

        def request = requestBuilder.build()
        def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw new IOException("Failed to upload part to S3: HTTP ${response.statusCode()} - ${response.body()}")
        }
    }

    /**
     * Detect content type from filename extension.
     */
    private String detectContentType(String fileName) {
        def extension = fileName.contains('.') ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : ''

        switch (extension) {
            case 'txt':
                return 'text/plain'
            case 'csv':
                return 'text/csv'
            case 'tsv':
                return 'text/tab-separated-values'
            case 'json':
                return 'application/json'
            case 'xml':
                return 'application/xml'
            case 'html':
            case 'htm':
                return 'text/html'
            case 'pdf':
                return 'application/pdf'
            case 'gz':
            case 'gzip':
                return 'application/gzip'
            case 'zip':
                return 'application/zip'
            case 'tar':
                return 'application/x-tar'
            case 'bam':
                return 'application/octet-stream'
            case 'vcf':
                return 'text/plain'
            case 'fastq':
            case 'fq':
                return 'text/plain'
            case 'fasta':
            case 'fa':
                return 'text/plain'
            case 'bed':
                return 'text/plain'
            default:
                return 'application/octet-stream'
        }
    }
}
