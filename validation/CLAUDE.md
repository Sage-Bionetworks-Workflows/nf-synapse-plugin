<!-- Last reviewed: 2026-03 -->

## Project

Integration test workflows that exercise the plugin against a live Synapse instance. These are not unit tests — they make real API calls and require authentication.

## Commands

```bash
# Set auth token first
nextflow secrets set SYNAPSE_AUTH_TOKEN <your-personal-access-token>

# Read validation (direct access + samplesheet)
nextflow run validation/main.nf --synapse_id syn1234567

# Read + write (FizzBuzz round-trip with subfolder creation)
nextflow run validation/main_fizzbuzz.nf --input syn://syn72514512 --outDir syn://syn72507092

# Write-only validation
nextflow run validation/main_write.nf --input <local-file> --outDir syn://synXXXXX
```

## Conventions

- All workflows use `nextflow.enable.dsl = 2`.
- `nextflow.config` declares the plugin (`nf-synapse@0.1.0`) and reads auth from secrets. Update the version here when testing a new release.
- CI runs these after `make install` — the installed plugin version must match what `nextflow.config` declares.

## Constraints

- **Requires SYNAPSE_AUTH_TOKEN** — a Synapse Personal Access Token. Without it, all workflows fail at authentication.
- **Requires real Synapse entities** — the syn IDs in CI and examples must point to existing entities the token has access to. Do not use placeholder IDs.
- **Write tests create real data** — `main_fizzbuzz.nf` and `main_write.nf` upload files to Synapse. Use a test folder, not production data.
