#!/usr/bin/env nextflow

/*
 * Validation pipeline for nf-synapse plugin WRITE support
 *
 * Usage:
 *   1. Set your Synapse auth token:
 *      nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxxxxxxxxx
 *
 *   2. Run this pipeline with a Synapse folder ID:
 *      nextflow run main_write.nf --outDir syn://syn72507092
 *
 * The pipeline will generate test output files and publish them to the
 * specified Synapse folder.
 */

nextflow.enable.dsl = 2

params.outDir = null

// Process that generates output to be published to Synapse
process GENERATE_OUTPUT {
    publishDir params.outDir, mode: 'copy'

    output:
    path "test_output.txt"
    path "test_data.csv"

    script:
    """
    echo "Test output from nf-synapse write validation" > test_output.txt
    echo "Generated at: \$(date)" >> test_output.txt
    echo "Hostname: \$(hostname)" >> test_output.txt

    echo "id,value,description" > test_data.csv
    echo "1,100,First row" >> test_data.csv
    echo "2,200,Second row" >> test_data.csv
    echo "3,300,Third row" >> test_data.csv
    """
}

// Process that processes input and produces output
process PROCESS_AND_OUTPUT {
    publishDir params.outDir, mode: 'copy'

    input:
    val message

    output:
    path "processed_*.txt"

    script:
    """
    echo "Processed message: ${message}" > processed_\${RANDOM}.txt
    echo "Timestamp: \$(date)" >> processed_\${RANDOM}.txt
    """
}

// Main workflow
workflow {
    if (params.outDir) {
        log.info "=========================================="
        log.info "nf-synapse Write Validation"
        log.info "=========================================="
        log.info "Output directory: ${params.outDir}"
        log.info ""

        // Generate and publish outputs
        GENERATE_OUTPUT()

        // Process some input and publish
        Channel.of("Hello from Nextflow!", "Testing Synapse upload")
            | PROCESS_AND_OUTPUT

        log.info "Check your Synapse folder for the uploaded files!"
    }
    else {
        log.info """
        ========================================
        nf-synapse Write Support Validation
        ========================================

        No output directory specified. Please provide a Synapse folder ID:

        nextflow run main_write.nf --outDir syn://syn72507092

        Prerequisites:
        - Set your Synapse auth token:
          nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxxxxxxxxx
        - Have CREATE permission on the target Synapse folder
        ========================================
        """
    }
}
