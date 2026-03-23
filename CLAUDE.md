<!-- Last reviewed: 2026-03 -->

## Project

Nextflow plugin that enables `syn://` URI access to Synapse (Sage Bionetworks) files in pipelines and samplesheets. Supports reading files/versions, writing via multipart upload, and auto-creating folder hierarchies. Published to the [Nextflow Plugin Registry](https://registry.nextflow.io).

## Stack

- **Language**: Groovy 3.0 on Java 21
- **Build**: Gradle 9.3.0 (wrapper) with `io.nextflow.nextflow-plugin` v1.0.0-beta.14
- **Nextflow**: 25.04.0+ required
- **Test**: Spock 2.3-groovy-3.0, WireMock 3.3.1, JUnit Platform
- **Plugin framework**: PF4J (via Nextflow's `BasePlugin`)

## Commands

```bash
make assemble        # ./gradlew assemble
make test            # ./gradlew test (unit tests only)
make clean           # rm -rf .nextflow* work build && ./gradlew clean
make install         # ./gradlew installPlugin (local Nextflow plugins dir)
make release         # ./gradlew releasePlugin (publishes to registry)
./gradlew build      # full build + test (used by CI)
```

## Data Models

### syn:// URI formats
- `syn://syn1234567` — latest version of entity
- `syn://syn1234567.5` — specific version
- `syn://syn1234567/filename.txt` — file in folder (write target)
- `syn://syn1234567/path/to/file.txt` — nested path (auto-creates folders on write)

Entity-only URIs are for reads. Folder/file URIs are for writes. `SynapsePath.isFolderFilePath()` distinguishes them.

### Synapse REST API contracts
- Entity metadata: `GET /repo/v1/entity/{synId}` — returns `concreteType`, `dataFileHandleId`, `name`, `etag`
- File download: `GET /file/v1/file/{handleId}?redirect=false` — returns presigned URL (via redirect or JSON)
- Folder children: `POST /repo/v1/entity/children` — returns `page[]` with `id`, `name`, `type`
- Multipart upload: 4-step protocol (see `client/CLAUDE.md`)

### META-INF registration (do not edit without updating source)
- `extensions.idx` → `nextflow.synapse.SynapsePathFactory` (PF4J extension point)
- `services/java.nio.file.spi.FileSystemProvider` → `nextflow.synapse.nio.SynapseFileSystemProvider` (Java NIO SPI)

Keep these in sync with `build.gradle` `extensionPoints` and the actual class names. Mismatches silently break plugin loading.

## Conventions

- Annotate all classes with `@CompileStatic` — required for Nextflow plugin performance.
- Use `@Slf4j` for logging (Groovy AST transform, not manual logger creation).
- Map HTTP status codes to Java NIO exceptions: 401→`SecurityException`, 403→`AccessDeniedException`, 404→`NoSuchFileException`.
- Error messages must be actionable: include what went wrong and how to fix it (e.g., "Set it via: `nextflow secrets set SYNAPSE_AUTH_TOKEN <your-token>`").
- Use Groovy idioms: safe navigation (`?.`), Elvis (`?:`), `withStream`/`withCloseable` for resource management.

## Architecture

```
Nextflow pipeline (syn:// URI in file(), publishDir, samplesheet)
  → SynapsePathFactory (@Extension, registered in extensions.idx)
    → SynapsePath + SynapseFileSystem
      → SynapseFileSystemProvider (registered in META-INF/services)
        ├─ Read: SynapseReadableByteChannel → presigned URL → HTTP stream
        └─ Write: SynapseWritableByteChannel → temp file → SynapseUploader → multipart upload
          → SynapseClient (REST API wrapper, Bearer auth)
            → Synapse API + S3
```

Auth fallback chain: `synapse.authToken` config → `SYNAPSE_AUTH_TOKEN` Nextflow secret → `SYNAPSE_AUTH_TOKEN` env var.

## Constraints

- **Version is set by env var, not build.gradle** — `PLUGIN_VERSION` env var, defaults to `0.1.0`. CI sets this from the GitHub release tag. Do not hardcode versions.
- **Release tags have no `v` prefix** — use `0.2.0`, not `v0.2.0`. The release workflow parses the tag directly as the version.
- **NPR_API_KEY secret required for registry publishing** — stored in GitHub repo secrets. Without it, `make release` silently fails.
- **Plugin structure matches nf-snowflake layout** — flattened (source at root, not nested under `plugins/`). This is required for the Nextflow plugin registry.
- **SynapseFileSystemProvider is a singleton** — `instance()` returns a shared instance. The file system is created once and reused.
- **delete() is a no-op** — Synapse doesn't support NIO deletion. Existing files get new versions on re-upload instead.
- **newDirectoryStream() returns empty** — Synapse folders don't support NIO listing. This prevents `publishDir` cleanup from failing.

## Testing

- **Unit tests**: Spock specs in `src/test/`. Use `def "should [behavior]"()` naming. WireMock stubs Synapse REST API.
- **Integration tests**: `validation/` directory — real Nextflow workflows against live Synapse. Require `SYNAPSE_AUTH_TOKEN` secret. Run via CI (`test.yml`) or locally.
- **No linter/formatter configs exist** — code style is convention-based, not tool-enforced.

## Related Systems

- **Synapse REST API**: `https://repo-prod.prod.sagebase.org` (prod endpoint). Auth via Personal Access Tokens.
- **nf-snowflake**: Reference template for Nextflow plugin structure. This repo's layout mirrors it.
- **Nextflow Plugin Registry**: Where releases are published. Requires NPR_API_KEY.
