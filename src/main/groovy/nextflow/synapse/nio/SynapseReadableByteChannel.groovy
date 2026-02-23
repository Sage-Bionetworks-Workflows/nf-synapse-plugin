package nextflow.synapse.nio

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.time.Duration

/**
 * Readable byte channel for streaming Synapse file downloads from presigned URLs.
 */
@Slf4j
@CompileStatic
class SynapseReadableByteChannel implements SeekableByteChannel {

    private final String presignedUrl
    private final long contentSize
    private final HttpClient httpClient
    private InputStream inputStream
    private long position = 0
    private volatile boolean open = true

    SynapseReadableByteChannel(String presignedUrl, long contentSize) {
        this.presignedUrl = presignedUrl
        this.contentSize = contentSize
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private synchronized void ensureOpen() {
        if (!open) {
            throw new ClosedChannelException()
        }
        if (inputStream == null) {
            openStream()
        }
    }

    private void openStream() {
        log.debug("Opening stream from presigned URL")

        def request = HttpRequest.newBuilder()
            .uri(URI.create(presignedUrl))
            .GET()
            .timeout(Duration.ofMinutes(30))
            .build()

        def response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download file: HTTP ${response.statusCode()}")
        }

        inputStream = response.body()
    }

    @Override
    int read(ByteBuffer dst) throws IOException {
        ensureOpen()

        def remaining = dst.remaining()
        def buffer = new byte[remaining]
        def bytesRead = inputStream.read(buffer)

        if (bytesRead == -1) {
            return -1
        }

        dst.put(buffer, 0, bytesRead)
        position += bytesRead

        return bytesRead
    }

    @Override
    int write(ByteBuffer src) throws IOException {
        throw new UnsupportedOperationException("Write not supported (read-only)")
    }

    @Override
    long position() throws IOException {
        ensureOpen()
        return position
    }

    @Override
    SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition != position) {
            throw new UnsupportedOperationException(
                "Seeking not supported. Synapse downloads are streaming-only."
            )
        }
        return this
    }

    @Override
    long size() throws IOException {
        return contentSize
    }

    @Override
    SeekableByteChannel truncate(long size) throws IOException {
        throw new UnsupportedOperationException("Truncate not supported (read-only)")
    }

    @Override
    boolean isOpen() {
        return open
    }

    @Override
    synchronized void close() throws IOException {
        if (open) {
            open = false
            if (inputStream != null) {
                try {
                    inputStream.close()
                } catch (IOException e) {
                    log.debug("Error closing input stream", e)
                }
                inputStream = null
            }
        }
    }
}
