#!/usr/bin/env nextflow

/*
 * Validation pipeline for nf-synapse plugin
 *
 * Usage:
 *   1. Set your Synapse auth token:
 *      nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxxxxxxxxx
 *
 *   2. Run this pipeline with a real Synapse file ID:
 *      nextflow run main.nf --synapse_id syn1234567
 *
 *   3. Or test with a samplesheet:
 *      nextflow run main.nf --samplesheet samples.csv
 */

nextflow.enable.dsl = 2

params.synapse_id = null
params.samplesheet = null

// Test 1: Direct file access
workflow test_direct_access {
    take:
    synapse_id

    main:
    synapse_file = file("syn://${synapse_id}")

    Channel.of(synapse_file)
        | view { "Direct access - File: ${it.name}, Size: ${it.size()} bytes" }
}

// Test 2: Process using Synapse file
process COPY_FILE {
    debug true

    input:
    path input_file

    output:
    path "output_*"

    script:
    """
    echo "Processing file: ${input_file}"
    echo "File size: \$(wc -c < ${input_file}) bytes"
    cp ${input_file} output_${input_file}
    echo "Copy complete"
    """
}

workflow test_process {
    take:
    synapse_id

    main:
    synapse_file = file("syn://${synapse_id}")
    COPY_FILE(synapse_file)
}

// Test 3: Samplesheet with Synapse URIs
workflow test_samplesheet {
    take:
    samplesheet

    main:
    Channel.fromPath(samplesheet)
        .splitCsv(header: true)
        .map { row ->
            def synapse_file = file(row.synapse_uri)
            tuple(row.sample_id, synapse_file)
        }
        .view { sample_id, f -> "Samplesheet - Sample: ${sample_id}, File: ${f.name}" }
}

// Main workflow
workflow {
    if (params.synapse_id) {
        log.info "Testing with Synapse ID: ${params.synapse_id}"

        test_direct_access(params.synapse_id)
        test_process(params.synapse_id)
    }
    else if (params.samplesheet) {
        log.info "Testing with samplesheet: ${params.samplesheet}"

        test_samplesheet(params.samplesheet)
    }
    else {
        log.info """
        ========================================
        nf-synapse Plugin Validation
        ========================================

        No input specified. Please provide either:

        1. A Synapse ID for direct file access:
           nextflow run main.nf --synapse_id syn1234567

        2. A samplesheet with Synapse URIs:
           nextflow run main.nf --samplesheet samples.csv

        Example samplesheet format (samples.csv):
        sample_id,synapse_uri
        sample1,syn://syn1234567
        sample2,syn://syn1234567.5

        Prerequisites:
        - Set your Synapse auth token:
          nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxxxxxxxxx
        ========================================
        """
    }
}
