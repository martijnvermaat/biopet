/**
 * Biopet is built on top of GATK Queue for building bioinformatic
 * pipelines. It is mainly intended to support LUMC SHARK cluster which is running
 * SGE. But other types of HPC that are supported by GATK Queue (such as PBS)
 * should also be able to execute Biopet tools and pipelines.
 *
 * Copyright 2014 Sequencing Analysis Support Core - Leiden University Medical Center
 *
 * Contact us at: sasc@lumc.nl
 *
 * A dual licensing mode is applied. The source code within this project that are
 * not part of GATK Queue is freely available for non-commercial use under an AGPL
 * license; For commercial users or users who do not want to follow the AGPL
 * license, please contact us to obtain a separate license.
 */
package nl.lumc.sasc.biopet.extensions.picard

import java.io.File
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.utils.commandline.{ Input, Output, Argument }

/** Extension for picard MergeSamFiles */
class MergeSamFiles(val root: Configurable) extends Picard {
  javaMainClass = new picard.sam.MergeSamFiles().getClass.getName

  @Input(doc = "The input SAM or BAM files to analyze.", required = true)
  var input: List[File] = Nil

  @Output(doc = "The output file to bam file to", required = true)
  var output: File = _

  @Argument(doc = "Sort order of output file Required. Possible values: {unsorted, queryname, coordinate} ", required = true)
  var sortOrder: String = _

  @Argument(doc = "ASSUME_SORTED", required = false)
  var assumeSorted: Boolean = config("assumesorted", default = false)

  @Argument(doc = "MERGE_SEQUENCE_DICTIONARIES", required = false)
  var mergeSequenceDictionaries: Boolean = config("merge_sequence_dictionaries", default = false)

  @Argument(doc = "USE_THREADING", required = false)
  var useThreading: Boolean = config("use_threading", default = false)

  @Argument(doc = "COMMENT", required = false)
  var comment: Option[String] = config("comment")

  /** Returns command to execute */
  override def commandLine = super.commandLine +
    repeat("INPUT=", input, spaceSeparated = false) +
    required("OUTPUT=", output, spaceSeparated = false) +
    required("SORT_ORDER=", sortOrder, spaceSeparated = false) +
    conditional(assumeSorted, "ASSUME_SORTED=TRUE") +
    conditional(mergeSequenceDictionaries, "MERGE_SEQUENCE_DICTIONARIES=TRUE") +
    optional("COMMENT=", comment, spaceSeparated = false)
}

object MergeSamFiles {
  /** Returns default MergeSamFiles */
  def apply(root: Configurable, input: List[File], outputDir: File, sortOrder: String = null): MergeSamFiles = {
    val mergeSamFiles = new MergeSamFiles(root)
    mergeSamFiles.input = input
    mergeSamFiles.output = new File(outputDir, input.head.getName.stripSuffix(".bam").stripSuffix(".sam") + ".merge.bam")
    if (sortOrder == null) mergeSamFiles.sortOrder = "coordinate"
    else mergeSamFiles.sortOrder = sortOrder
    mergeSamFiles
  }
}