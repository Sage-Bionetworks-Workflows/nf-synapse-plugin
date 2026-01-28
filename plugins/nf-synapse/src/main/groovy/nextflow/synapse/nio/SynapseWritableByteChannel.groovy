package nextflow.synapse.nio

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.synapse.client.SynapseClient
import nextflow.synapse.client.SynapseUploader

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A writable byte channel that buffers content to a temp file and uploads to Synapse on close.
 */
@Slf4j
@CompileStatic
class SynapseWritableByteChannel implements SeekableByteChannel {

    private final SynapseClient client
    private final String parentFolderId
    private final String fileName
    private final Path tempFile
    private final SeekableByteChannel tempChannel
    private boolean open = true

    SynapseWritableByteChannel(SynapseClient client, String parentFolderId, String fileName) {
        this.client = client
        this.parentFolderId = parentFolderId
        this.fileName = fileName
        this.tempFile = Files.createTempFile("synapse-upload-", ".tmp")
        this.tempChannel = Files.newByteChannel(tempFile,
            StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        log.debug("Created writable channel for {} in folder {}, temp file: {}", fileName, parentFolderId, tempFile)
    }

    @Override
    int read(ByteBuffer dst) throws IOException {
        throw new UnsupportedOperationException("Read not supported on writable channel")
    }

    @Override
    int write(ByteBuffer src) throws IOException {
        return tempChannel.write(src)
    }

    @Override
    long position() throws IOException {
        return tempChannel.position()
    }

    @Override
    SeekableByteChannel position(long newPosition) throws IOException {
        tempChannel.position(newPosition)
        return this
    }

    @Override
    long size() throws IOException {
        return tempChannel.size()
    }

    @Override
    SeekableByteChannel truncate(long size) throws IOException {
        tempChannel.truncate(size)
        return this
    }

    @Override
    boolean isOpen() {
        return open
    }

    @Override
    void close() throws IOException {
        if (!open) return
        open = false

        try {
            // Close the temp channel
            tempChannel.close()

            // Upload the temp file to Synapse
            log.info("Uploading {} to Synapse folder {}", fileName, parentFolderId)
            def uploader = new SynapseUploader(client)
            uploader.uploadFile(tempFile, parentFolderId, fileName)
            log.info("Upload complete: {}", fileName)
        } finally {
            // Clean up temp file
            try {
                Files.deleteIfExists(tempFile)
            } catch (Exception e) {
                log.warn("Failed to delete temp file: {}", tempFile, e)
            }
        }
    }
}
