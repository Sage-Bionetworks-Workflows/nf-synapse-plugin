#!/usr/bin/env nextflow

/*
 * FizzBuzz validation pipeline for nf-synapse plugin subfolder support
 *
 * Usage:
 *   1. Set your Synapse auth token:
 *      nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxxxxxxxxx
 *
 *   2. Run this pipeline with input file and output folder:
 *      nextflow run main_fizzbuzz.nf --input syn://syn72514512 --outDir syn://syn72507092
 *
 * The input file should contain a single integer.
 * The pipeline will run FizzBuzz and save results to outDir/<n>/result.txt
 *
 * Example:
 *   Input file contains: 15
 *   Output: outDir/15/result.txt containing "1, 2, Fizz, 4, Buzz, ..."
 */

nextflow.enable.dsl = 2

params.input = null
params.outDir = null

process FIZZBUZZ {
    input:
    path input_file

    output:
    tuple env(N), path("result.txt")

    script:
    """
    # Read the number from input file
    N=\$(cat ${input_file} | tr -d '[:space:]')

    # Run FizzBuzz with Python
    python3 << 'PYTHON_SCRIPT'
n = int(open("${input_file}").read().strip())

results = []
for i in range(1, n + 1):
    if i % 15 == 0:
        results.append("FizzBuzz")
    elif i % 3 == 0:
        results.append("Fizz")
    elif i % 5 == 0:
        results.append("Buzz")
    else:
        results.append(str(i))

with open("result.txt", "w") as f:
    f.write(", ".join(results))
PYTHON_SCRIPT
    """
}

process PUBLISH_RESULT {
    publishDir "${params.outDir}/${n}", mode: 'copy'

    input:
    tuple val(n), path(result)

    output:
    path "result.txt"

    script:
    """
    cp ${result} result.txt
    """
}

workflow {
    if (params.input && params.outDir) {
        log.info "=========================================="
        log.info "nf-synapse FizzBuzz Validation"
        log.info "=========================================="
        log.info "Input file: ${params.input}"
        log.info "Output directory: ${params.outDir}"
        log.info ""

        // Create channel from the input file
        input_ch = Channel.of(file(params.input))

        // Run FizzBuzz and publish
        FIZZBUZZ(input_ch) | PUBLISH_RESULT

        log.info "Check your Synapse folder for the uploaded results!"
    }
    else {
        log.info """
        ========================================
        nf-synapse FizzBuzz Validation
        ========================================

        This pipeline tests subfolder support for Synapse uploads.

        Usage:
        nextflow run main_fizzbuzz.nf --input syn://syn72514512 --outDir syn://syn72507092

        The input file should contain a single integer (e.g., "15").
        Results will be saved to outDir/<n>/result.txt

        Example:
        - Input file contains: 15
        - Output: outDir/15/result.txt containing "1, 2, Fizz, 4, Buzz, ..."

        Prerequisites:
        - Set your Synapse auth token:
          nextflow secrets set SYNAPSE_AUTH_TOKEN syn_pat_xxxxxxxxxx
        - Have READ permission on the input file
        - Have CREATE permission on the target Synapse folder
        ========================================
        """
    }
}
