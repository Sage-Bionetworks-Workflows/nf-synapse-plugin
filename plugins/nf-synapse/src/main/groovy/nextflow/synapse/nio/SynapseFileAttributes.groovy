package nextflow.synapse.nio

import groovy.transform.CompileStatic

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * File attributes for Synapse entities.
 */
@CompileStatic
class SynapseFileAttributes implements BasicFileAttributes {

    private final Map entity
    private final FileTime lastModifiedTime
    private final FileTime creationTime
    private final long size
    private final boolean isFile
    private final String entityId

    SynapseFileAttributes(Map entity) {
        this.entity = entity
        this.entityId = entity.id as String
        this.size = parseSize(entity)
        this.lastModifiedTime = parseTime(entity.modifiedOn as String)
        this.creationTime = parseTime(entity.createdOn as String)
        this.isFile = isFileEntity(entity.concreteType as String)
    }

    private static long parseSize(Map entity) {
        // Try fileSize first (from FileEntity)
        def fileSize = entity.get('fileSize')
        if (fileSize != null) {
            return fileSize as Long
        }
        // Fallback to 0 for non-file entities
        return 0L
    }

    private static FileTime parseTime(String timestamp) {
        if (!timestamp) {
            return FileTime.from(Instant.EPOCH)
        }
        try {
            // Synapse uses ISO 8601 format
            def instant = Instant.parse(timestamp)
            return FileTime.from(instant)
        } catch (Exception e) {
            return FileTime.from(Instant.EPOCH)
        }
    }

    private static boolean isFileEntity(String concreteType) {
        return concreteType?.endsWith('.FileEntity')
    }

    @Override
    FileTime lastModifiedTime() {
        return lastModifiedTime
    }

    @Override
    FileTime lastAccessTime() {
        // Synapse doesn't track access time, return modified time
        return lastModifiedTime
    }

    @Override
    FileTime creationTime() {
        return creationTime
    }

    @Override
    boolean isRegularFile() {
        return isFile
    }

    @Override
    boolean isDirectory() {
        return !isFile
    }

    @Override
    boolean isSymbolicLink() {
        return false
    }

    @Override
    boolean isOther() {
        return false
    }

    @Override
    long size() {
        return size
    }

    @Override
    Object fileKey() {
        return entityId
    }

    String getEntityId() {
        return entityId
    }

    String getName() {
        return entity.name as String
    }

    String getContentType() {
        return entity.contentType as String
    }

    String getMd5() {
        return entity.md5 as String
    }

    Integer getVersionNumber() {
        return entity.versionNumber as Integer
    }

    String getVersionLabel() {
        return entity.versionLabel as String
    }
}
