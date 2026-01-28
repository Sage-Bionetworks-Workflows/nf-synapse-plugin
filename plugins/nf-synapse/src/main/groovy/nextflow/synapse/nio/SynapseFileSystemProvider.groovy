package nextflow.synapse.nio

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.synapse.SynapseConfig
import nextflow.synapse.client.SynapseUploader

import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider

/**
 * FileSystemProvider for the "syn" URI scheme.
 */
@Slf4j
@CompileStatic
class SynapseFileSystemProvider extends FileSystemProvider {

    private static SynapseFileSystemProvider INSTANCE

    private SynapseFileSystem fileSystem

    static synchronized SynapseFileSystemProvider instance() {
        if (INSTANCE == null) {
            INSTANCE = new SynapseFileSystemProvider()
        }
        return INSTANCE
    }

    @Override
    String getScheme() {
        return "syn"
    }

    @Override
    synchronized FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        if (fileSystem != null && fileSystem.isOpen()) {
            return fileSystem
        }

        def config = env?.get('config') as SynapseConfig
        if (config == null) {
            config = new SynapseConfig([:])
        }

        fileSystem = new SynapseFileSystem(this, config)
        return fileSystem
    }

    @Override
    synchronized FileSystem getFileSystem(URI uri) {
        return fileSystem
    }

    @Override
    Path getPath(URI uri) {
        def fs = getFileSystem(uri) ?: newFileSystem(uri, [:])
        return new SynapsePath(fs as SynapseFileSystem, uri.toString())
    }

    @Override
    SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
        def synapsePath = toSynapsePath(path)
        def fs = synapsePath.fileSystem as SynapseFileSystem
        def client = fs.client

        // Check if this is a write operation
        if (options.contains(java.nio.file.StandardOpenOption.WRITE) ||
            options.contains(java.nio.file.StandardOpenOption.CREATE) ||
            options.contains(java.nio.file.StandardOpenOption.CREATE_NEW)) {

            // For write operations, this must be a folder/file path (e.g., syn://folder/filename.txt)
            if (!synapsePath.isFolderFilePath()) {
                throw new IllegalArgumentException(
                    "Cannot write to ${synapsePath.synId}: path must include a filename (e.g., syn://folder/filename.txt)"
                )
            }

            // Return a writable channel that buffers to temp file and uploads on close
            return new SynapseWritableByteChannel(client, synapsePath.parentFolderId, synapsePath.fileNameString)
        }

        // Read operation - get entity metadata
        def entity = client.getEntity(synapsePath.synId, synapsePath.version)

        // Verify it's a file entity
        def concreteType = entity.concreteType as String
        if (!concreteType?.endsWith('.FileEntity')) {
            throw new IllegalArgumentException(
                "Cannot read ${synapsePath.synId}: entity is a ${concreteType?.split('\\.')?.last() ?: 'unknown type'}, not a file. " +
                "Only FileEntity types can be downloaded."
            )
        }

        def fileHandleId = entity.dataFileHandleId as String
        if (!fileHandleId) {
            throw new IllegalStateException("No file handle found for ${synapsePath.synId}")
        }

        // Get presigned URL
        def presignedUrl = client.getPresignedUrl(synapsePath.synId, fileHandleId)

        // Get file size for the channel
        def contentSize = entity.get('fileSize') as Long ?: -1L

        return new SynapseReadableByteChannel(presignedUrl, contentSize)
    }

    @Override
    DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) {
        // Return an empty directory stream - Synapse folders don't support listing via NIO
        // This allows operations like deleteDir to complete without error
        return new DirectoryStream<Path>() {
            @Override
            Iterator<Path> iterator() {
                return Collections.<Path>emptyList().iterator()
            }
            @Override
            void close() {}
        }
    }

    @Override
    void createDirectory(Path dir, FileAttribute<?>... attrs) {
        // For Synapse, we don't create directories - they must already exist as Folders
        // This is called by Nextflow's publishDir, so we verify the folder exists
        def synapsePath = toSynapsePath(dir)
        def fs = synapsePath.fileSystem as SynapseFileSystem

        // If this is a folder/file path, we're checking the parent folder
        def folderId = synapsePath.isFolderFilePath() ? synapsePath.parentFolderId : synapsePath.synId

        if (!fs.client.isFolder(folderId)) {
            throw new UnsupportedOperationException(
                "Cannot create directory: ${folderId} is not a Synapse Folder. " +
                "Parent folder must already exist in Synapse."
            )
        }
        // Folder exists - this is a no-op
        log.debug("Directory creation request for existing Synapse folder: {}", folderId)
    }

    @Override
    void delete(Path path) {
        // No-op for Synapse paths - we don't support deletion, but we don't want to fail
        // the publish operation either. Synapse will create new versions if files exist.
        log.debug("Delete requested for Synapse path {} - ignoring", path)
    }

    @Override
    void copy(Path source, Path target, CopyOption... options) {
        // Handle local -> Synapse upload
        if (target instanceof SynapsePath && !(source instanceof SynapsePath)) {
            uploadLocalFile(source, (SynapsePath) target)
            return
        }

        // Handle Synapse -> local download
        if (source instanceof SynapsePath && !(target instanceof SynapsePath)) {
            downloadToLocal((SynapsePath) source, target)
            return
        }

        throw new UnsupportedOperationException("Copy between Synapse paths is not supported")
    }

    /**
     * Upload a local file to Synapse.
     */
    private void uploadLocalFile(Path source, SynapsePath target) {
        log.debug("Uploading local file {} to Synapse {}", source, target.toUriString())

        def fs = target.fileSystem as SynapseFileSystem

        // Determine parent folder and filename
        String parentFolderId
        String fileName

        if (target.isFolderFilePath()) {
            // Target is syn://folder/filename.txt format
            parentFolderId = target.parentFolderId
            fileName = target.fileNameString
        } else {
            // Target is syn://folder format - use source filename
            parentFolderId = target.synId
            fileName = source.fileName.toString()
        }

        // Upload using SynapseUploader
        def uploader = new SynapseUploader(fs.client)
        uploader.uploadFile(source, parentFolderId, fileName)
    }

    /**
     * Download a Synapse file to local filesystem.
     */
    private void downloadToLocal(SynapsePath source, Path target) {
        log.debug("Downloading Synapse {} to local file {}", source.toUriString(), target)

        // Use the byte channel to stream download
        newByteChannel(source, Collections.emptySet()).withCloseable { channel ->
            Files.newOutputStream(target).withStream { out ->
                def buffer = java.nio.ByteBuffer.allocate(8192)
                while (channel.read(buffer) != -1) {
                    buffer.flip()
                    def bytes = new byte[buffer.remaining()]
                    buffer.get(bytes)
                    out.write(bytes)
                    buffer.clear()
                }
            }
        }
    }

    @Override
    void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException("Move not supported (read-only)")
    }

    @Override
    boolean isSameFile(Path path1, Path path2) {
        def p1 = toSynapsePath(path1)
        def p2 = toSynapsePath(path2)
        return p1.synId == p2.synId &&
               p1.version == p2.version &&
               p1.fileNameString == p2.fileNameString
    }

    @Override
    boolean isHidden(Path path) {
        return false
    }

    @Override
    FileStore getFileStore(Path path) {
        throw new UnsupportedOperationException("FileStore not supported")
    }

    @Override
    void checkAccess(Path path, AccessMode... modes) {
        def synapsePath = toSynapsePath(path)
        def fs = synapsePath.fileSystem as SynapseFileSystem

        // For folder/file paths (e.g., syn://syn123/subdir/file.txt), we can't easily check
        // if the specific file exists without listing folder contents. Since these paths
        // represent upload destinations that don't exist yet, throw NoSuchFileException.
        if (synapsePath.isFolderFilePath()) {
            throw new java.nio.file.NoSuchFileException(synapsePath.fileNameString,
                null, "Synapse folder/file paths are write-only destinations")
        }

        // For write access, verify the target is a folder
        for (mode in modes) {
            if (mode == AccessMode.WRITE) {
                // Write access is allowed for folder paths (upload destinations)
                if (!fs.client.isFolder(synapsePath.synId)) {
                    throw new UnsupportedOperationException(
                        "Write access requires a Folder entity, but ${synapsePath.synId} is not a Folder"
                    )
                }
                return
            }
        }

        // For read access, verify entity exists by fetching metadata
        fs.client.getEntity(synapsePath.synId, synapsePath.version)
    }

    @Override
    <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return null
    }

    @Override
    <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) {
        if (type != BasicFileAttributes.class && type != SynapseFileAttributes.class) {
            throw new UnsupportedOperationException("Attribute type ${type} not supported")
        }

        def synapsePath = toSynapsePath(path)
        def fs = synapsePath.fileSystem as SynapseFileSystem
        def entity = fs.client.getEntity(synapsePath.synId, synapsePath.version)

        return (A) new SynapseFileAttributes(entity)
    }

    @Override
    Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
        def attrs = readAttributes(path, BasicFileAttributes.class, options)
        def result = new HashMap<String, Object>()

        // Handle "basic:*" or just "*"
        def attrName = attributes.contains(':') ? attributes.split(':')[1] : attributes

        if (attrName == '*' || attrName == 'size') {
            result.put('size', attrs.size())
        }
        if (attrName == '*' || attrName == 'lastModifiedTime') {
            result.put('lastModifiedTime', attrs.lastModifiedTime())
        }
        if (attrName == '*' || attrName == 'isDirectory') {
            result.put('isDirectory', attrs.isDirectory())
        }
        if (attrName == '*' || attrName == 'isRegularFile') {
            result.put('isRegularFile', attrs.isRegularFile())
        }
        if (attrName == '*' || attrName == 'isSymbolicLink') {
            result.put('isSymbolicLink', attrs.isSymbolicLink())
        }
        if (attrName == '*' || attrName == 'isOther') {
            result.put('isOther', attrs.isOther())
        }

        return result
    }

    @Override
    void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new UnsupportedOperationException("Setting attributes not supported (read-only)")
    }

    private static SynapsePath toSynapsePath(Path path) {
        if (path instanceof SynapsePath) {
            return (SynapsePath) path
        }
        throw new IllegalArgumentException("Expected SynapsePath but got: ${path.class.name}")
    }
}
