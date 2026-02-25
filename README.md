# nf-synapse

A Nextflow plugin for accessing Synapse (Sage Bionetworks) files using `syn://` URIs.

## Features

- **Read**: Access Synapse files directly in pipelines using `syn://syn1234567` URIs
- **Write**: Publish pipeline outputs to Synapse folders using `publishDir`
- Support for versioned files: `syn://syn1234567.5`
- Automatic subfolder creation when publishing to nested paths
- Files retain their original Synapse filename when staged
- Use Synapse URIs in samplesheets
- Secure authentication via Nextflow secrets

## Synapse + Nextflow: Choosing the Right Tool

There are several approaches for Synapse + Nextflow integration:

| | **This plugin** | **[nf-syn](https://github.com/Sage-Bionetworks-Workflows/nf-syn)** | **[nf-synapse workflow](https://github.com/Sage-Bionetworks-Workflows/nf-synapse)** |
|---|-----------------|-------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| **What it is** | Nextflow plugin | Nextflow plugin | Standalone Nextflow workflow |
| **Status** | Active | Unmaintained (4+ years) | Active |
| **Integration** | Native `syn://` URIs | Native `syn://` URIs | Separate workflow run before/after |
| **Write support** | Yes (publishDir) | No (read-only) | Yes (indexing) |
| **Data storage** | Synapse storage | Synapse storage | Your cloud bucket (indexed in Synapse) |
| **Parallelization** | Sequential | Sequential | Parallel (per process) |
| **Best for** | Transparent file access | Legacy pipelines | Large-scale batch operations |

**Use this plugin when:**
- You want `syn://` URIs to "just work" transparently in your pipeline
- You're reading a few Synapse files as process inputs
- You want to publish outputs directly to a Synapse project folder
- You prefer simplicity over parallelization

**Use the [nf-synapse workflow](https://github.com/Sage-Bionetworks-Workflows/nf-synapse) when:**
- You need to stage/upload many files in parallel as a separate step
- You want data to remain in your cloud bucket (S3/GCS) with Synapse indexing
- You're building production pipelines with distinct data staging phases
- You need maximum throughput for large-scale transfers

**Note:** [nf-syn](https://github.com/Sage-Bionetworks-Workflows/nf-syn) was an earlier plugin with similar goals to this one but is no longer maintained and lacks write support.

## Requirements

- Nextflow 25.04.0+

## Installation

```bash
# Clone the repo
git clone https://github.com/Sage-Bionetworks-Workflows/nf-synapse-plugin.git
cd nf-synapse-plugin

# Build and install to ~/.nextflow/plugins
make install
```

Then add to your `nextflow.config`:

```groovy
plugins {
    id 'nf-synapse@0.1.0'
}

synapse {
    authToken = secrets.SYNAPSE_AUTH_TOKEN
}
```

## Configuration

### Authentication

Set your Synapse Personal Access Token as a Nextflow secret:

```bash
nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxxxxxxxxx
```

You can create a Personal Access Token at: https://www.synapse.org/#!PersonalAccessTokens:

### Optional Settings

```groovy
synapse {
    authToken = secrets.SYNAPSE_AUTH_TOKEN
    endpoint = 'https://repo-prod.prod.sagebase.org'  // default
}
```

## Usage

### Direct File Access

```groovy
// Access a Synapse file (latest version)
synapse_file = file('syn://syn26947830')

// Access a specific version
versioned_file = file('syn://syn26947830.1')
```

Files are automatically downloaded and staged with their original Synapse filename (e.g., `film_default.png`).

### In a Process

```groovy
process ANALYZE {
    input:
    path input_file

    output:
    path "result.txt"

    script:
    """
    echo "Processing: ${input_file}"
    analyze.sh ${input_file} > result.txt
    """
}

workflow {
    synapse_file = file('syn://syn26947830')
    ANALYZE(synapse_file)
}
```

### With Samplesheets

```groovy
// samplesheet.csv:
// sample_id,synapse_uri
// sample1,syn://syn26947830
// sample2,syn://syn26947830.1

Channel.fromPath('samplesheet.csv')
    .splitCsv(header: true)
    .map { row -> tuple(row.sample_id, file(row.synapse_uri)) }
    .set { samples_ch }

samples_ch.view { sample_id, f -> "Sample: ${sample_id}, File: ${f.name}" }
```

### Publishing to Synapse

Use `publishDir` to upload pipeline outputs to a Synapse folder:

```groovy
params.outDir = 'syn://syn25858544'  // Synapse folder ID

process ANALYZE {
    publishDir params.outDir, mode: 'copy'

    input:
    path input_file

    output:
    path "results.txt"

    script:
    """
    analyze.sh ${input_file} > results.txt
    """
}
```

The parent folder must already exist in Synapse and you must have CREATE permission.

### Publishing to Subfolders

Subfolders are automatically created when publishing to nested paths:

```groovy
process ANALYZE {
    publishDir "${params.outDir}/${sample_id}/results", mode: 'copy'

    input:
    tuple val(sample_id), path(input_file)

    output:
    path "output.txt"

    script:
    """
    analyze.sh ${input_file} > output.txt
    """
}
```

For example, publishing to `syn://syn25858544/sample1/results/output.txt` will:
1. Check if `sample1` folder exists under `syn25858544`, create if not
2. Check if `results` folder exists under `sample1`, create if not
3. Upload `output.txt` to the `results` folder

## URI Format

| Format | Description | Use Case |
|--------|-------------|----------|
| `syn://syn1234567` | File entity (latest version) | Reading files |
| `syn://syn1234567.5` | File entity (specific version) | Reading versioned files |
| `syn://syn1234567` | Folder entity | Writing/publishing files |
| `syn://syn1234567/subdir/file.txt` | File within folder | Writing to subfolders |

The same URI format works for both files and folders - the entity type in Synapse determines the behavior.

## Error Handling

| Error | Cause |
|-------|-------|
| `IllegalStateException: Synapse authentication token not configured` | Auth token not set |
| `SecurityException: authentication failed` | Invalid or expired token |
| `AccessDeniedException` | No permission to access entity |
| `NoSuchFileException` | Entity doesn't exist |
| `IllegalArgumentException: entity is a Folder, not a file` | Trying to download a Folder entity |
| `IllegalArgumentException: Cannot write to {synId}: not a Folder` | Trying to publish to a non-Folder entity |

## Building from Source

### Prerequisites

- Java 21+
- Gradle 8+ (or use the included wrapper)

### Build

```bash
make assemble   # Build the plugin
make test       # Run tests
make install    # Install to ~/.nextflow/plugins
make release    # Publish the plugin
```

## Releasing

1. [Create a new GitHub release](https://github.com/Sage-Bionetworks-Workflows/nf-synapse-plugin/releases/new) with the version as the tag (e.g. `0.2.0`) — no `v` prefix
2. Publishing the release triggers the release workflow, which publishes the plugin to the [Nextflow plugin registry](https://registry.nextflow.io)

> **Note:** The release workflow requires a `NPR_API_KEY` secret to be set in the repository settings. This is the access token from the [Nextflow plugin registry](https://registry.nextflow.io).

## Development

### Project Structure

```
nf-synapse-plugin/
├── build.gradle
├── settings.gradle
├── plugins/nf-synapse/
│   ├── build.gradle
│   └── src/
│       ├── main/groovy/nextflow/synapse/
│       │   ├── SynapsePlugin.groovy           # Plugin entry point
│       │   ├── SynapseConfig.groovy           # Configuration
│       │   ├── SynapsePathFactory.groovy      # Path factory extension
│       │   ├── client/
│       │   │   ├── SynapseClient.groovy       # REST API client
│       │   │   ├── SynapseAuthManager.groovy  # Token management
│       │   │   └── SynapseUploader.groovy     # Multipart upload handler
│       │   └── nio/
│       │       ├── SynapseFileSystemProvider.groovy  # "syn" scheme handler
│       │       ├── SynapseFileSystem.groovy
│       │       ├── SynapsePath.groovy         # URI parsing
│       │       ├── SynapseFileAttributes.groovy
│       │       ├── SynapseReadableByteChannel.groovy  # Download streaming
│       │       └── SynapseWritableByteChannel.groovy  # Upload streaming
│       └── test/groovy/nextflow/synapse/
│           ├── SynapsePathTest.groovy
│           ├── SynapseConfigTest.groovy
│           └── SynapseClientTest.groovy
└── validation/
    ├── main.nf              # Read validation
    ├── main_write.nf        # Write validation
    ├── main_fizzbuzz.nf     # Subfolder validation
    ├── nextflow.config
    └── samples.csv
```

### Running Validation

```bash
cd validation
nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxx

# Test direct file access (read)
nextflow run main.nf --synapse_id syn26947830

# Test versioned file (read)
nextflow run main.nf --synapse_id syn26947830.1

# Test samplesheet (read)
nextflow run main.nf --samplesheet samples.csv

# Test publishing to Synapse (write)
nextflow run main_write.nf --outDir syn://syn72507092

# Test subfolder creation (write)
nextflow run main_fizzbuzz.nf --input syn://syn72514512 --outDir syn://syn72507092
```

### Synapse API Flow

**Reading files:**
1. `GET /repo/v1/entity/{synId}` - Retrieve entity metadata (name, fileHandleId)
2. `GET /file/v1/file/{handleId}?redirect=false` - Get presigned download URL
3. Stream file content from presigned URL (no auth required)

**Writing files:**
1. `POST /repo/v1/entity/children` - List folder contents (check for existing subfolders)
2. `POST /repo/v1/entity` - Create Folder entities for subfolders (if needed)
3. `POST /file/v1/file/multipart` - Start multipart upload
4. `POST /file/v1/file/multipart/{uploadId}/presigned/url/batch` - Get presigned upload URLs
5. `PUT <presigned-url>` - Upload file parts to S3
6. `PUT /file/v1/file/multipart/{uploadId}/add/{partNum}` - Confirm part upload
7. `PUT /file/v1/file/multipart/{uploadId}/complete` - Complete upload, get FileHandle
8. `POST /repo/v1/entity` - Create FileEntity in parent folder

## License

Apache License 2.0
