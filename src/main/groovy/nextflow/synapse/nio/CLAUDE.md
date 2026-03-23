<!-- Last reviewed: 2026-03 -->

## Project

Java NIO FileSystem SPI implementation for `syn://` URIs. This is the layer that makes Synapse files work transparently with Nextflow's `file()`, `publishDir`, and process I/O.

## Conventions

- `SynapseFileSystemProvider` is a **singleton** — `instance()` returns the shared instance. One `SynapseFileSystem` is created and reused.
- `SynapsePath` is immutable after construction. `cachedFileName` uses double-checked locking for lazy initialization.
- Entity-only paths (`syn://syn123`) are for reads. Folder/file paths (`syn://syn123/file.txt`) are for writes. Use `isFolderFilePath()` to distinguish.

## Architecture

### SynapsePath identity
- `equals()` and `hashCode()` use `synId` + `version` + `fileSystem` identity only. **fileName is excluded** — this is intentional (GitHub issue #6 documents the decision). Two paths with the same synId/version but different fileNames are considered equal.
- `toString()` calls `getActualFileName()`, which fetches the entity name from Synapse API on first call (lazy, cached). Falls back to synId on error — this silent catch is a known concern (GitHub issue #4).
- `compareTo()` includes fileName for ordering, even though `equals()` does not.

### URI parsing
Two regex patterns in `SynapsePath`:
- `SYNAPSE_ID_PATTERN`: `syn(\d+)(\.(\d+))?` — matches `syn1234567` or `syn1234567.5`
- `SYNAPSE_FOLDER_FILE_PATTERN`: `syn(\d+)\/(.+)` — matches `syn1234567/filename.txt` or `syn1234567/path/to/file.txt`

Folder/file pattern is tried first (more specific).

### Read channel (SynapseReadableByteChannel)
- Lazy-opens HTTP stream on first `read()` call, not on construction.
- Forward-only: `position(newPos)` throws `UnsupportedOperationException` if `newPos != currentPosition`.
- `write()`, `truncate()` throw `UnsupportedOperationException`.

### Write channel (SynapseWritableByteChannel)
- All writes go to a temp file (`synapse-upload-*.tmp`).
- `close()` triggers the actual upload via `SynapseUploader`, then deletes the temp file.
- Supports `position()` and `truncate()` (delegated to temp file channel).
- `read()` throws `UnsupportedOperationException`.

## Constraints

- **newDirectoryStream() returns empty iterator** — Synapse folders don't support NIO listing. This is required so `publishDir` cleanup doesn't fail.
- **delete() is a no-op** — Synapse doesn't support deletion via this plugin. Files get new versions on re-upload.
- **move() throws UnsupportedOperationException** — no rename/move support.
- **copy() handles cross-filesystem only** — local→Synapse (upload) and Synapse→local (download). Synapse→Synapse copy is not supported.
- **checkAccess() for folder/file paths throws NoSuchFileException** — these are write-only destinations that don't exist as entities yet. This signals Nextflow to proceed with creation.
- **createDirectory() is a validation-only no-op** — verifies the folder exists in Synapse but doesn't create it. Actual folder creation happens in `SynapseUploader.ensureFolderPath()`.
