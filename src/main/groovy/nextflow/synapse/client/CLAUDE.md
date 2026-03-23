<!-- Last reviewed: 2026-03 -->

## Project

REST API client layer for Synapse. Handles authentication, entity CRUD, file downloads via presigned URLs, and multipart uploads to S3.

## Conventions

- `SynapseClient` wraps all HTTP calls. Never make raw HTTP requests from outside this package.
- Two `HttpClient` instances in `SynapseClient`: one follows redirects (normal API calls), one does not (`noRedirectClient` for presigned URL extraction from 307 responses).
- `SynapseUploader` creates its own `HttpClient` for S3 uploads — these go directly to S3, not through Synapse API.
- Auth is managed by `SynapseAuthManager` — always use `getAuthorizationHeader()`, never construct Bearer tokens manually.

## Architecture

### Multipart upload protocol (4 steps)
1. `startMultipartUpload` — sends file metadata + MD5. Synapse may return `COMPLETED` immediately if the file already exists (deduplication by MD5).
2. `getPresignedUploadUrls` — batch request for S3 presigned PUT URLs, one per part.
3. Upload parts to S3 + `addUploadPart` — PUT bytes to S3, then confirm each part with Synapse (includes part MD5).
4. `completeMultipartUpload` — returns `resultFileHandleId`.

After upload: `createFileEntity` (new file) or `updateFileEntity` (new version of existing file, requires current `etag`).

### Content-type detection
`SynapseUploader.detectContentType()` maps file extensions to MIME types. Includes bioinformatics formats: BAM, VCF, FASTQ/FQ, FASTA/FA, BED. Unknown extensions default to `application/octet-stream`.

### Part sizing
- Minimum 5MB, maximum 5GB per part. Maximum 10,000 parts (S3 limit).
- `calculatePartSize()` doubles from 5MB until `fileSize / partSize <= 10000`.

## Constraints

- **MD5 is streamed in 2MB chunks** — never buffer the entire file for hashing. This is deliberate to handle large genomics files.
- **readFilePart uses RandomAccessFile** — seeks to offset and reads only one part. Known resource leak risk if exception occurs between open and close (GitHub issue #5).
- **No retry logic** — all HTTP calls fail immediately on error. GitHub issue #7 tracks adding exponential backoff for transient failures (5xx, timeouts).
- **HttpClient per-instance** — `SynapseUploader` creates a new `HttpClient`. GitHub issue #8 tracks sharing a single client.
- **Folder creation is recursive** — `ensureFolderPath("results/qc")` creates `results/` then `qc/` inside it, checking for existing folders at each level to avoid duplicates.
