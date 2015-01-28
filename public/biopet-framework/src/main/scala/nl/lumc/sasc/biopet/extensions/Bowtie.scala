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
package nl.lumc.sasc.biopet.extensions

import java.io.File

import nl.lumc.sasc.biopet.core.BiopetCommandLineFunction
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.utils.commandline.{ Input, Output }

class Bowtie(val root: Configurable) extends BiopetCommandLineFunction {
  @Input(doc = "Fastq file R1", shortName = "R1")
  var R1: File = _

  @Input(doc = "Fastq file R2", shortName = "R2", required = false)
  var R2: File = _

  @Input(doc = "The reference file for the bam files.", shortName = "R")
  var reference: File = config("reference", required = true)

  @Output(doc = "Output file SAM", shortName = "output")
  var output: File = _

  executable = config("exe", default = "bowtie", freeVar = false)
  override val versionRegex = """.*[Vv]ersion:? (.*)""".r
  override val versionExitcode = List(0, 1)
  override def versionCommand = executable + " --version"

  override val defaultVmem = "6G"
  override val defaultThreads = 8

  var sam: Boolean = config("sam", default = true)
  var sam_RG: String = config("sam-RG")
  var seedlen: Option[Int] = config("seedlen")
  var seedmms: Option[Int] = config("seedmms")
  var k: Option[Int] = config("k")
  var m: Option[Int] = config("m")
  var best: Boolean = config("best", default = false)
  var maxbts: Option[Int] = config("maxbts")
  var strata: Boolean = config("strata", default = false)
  var maqerr: Option[Int] = config("maqerr")

  def cmdLine = {
    required(executable) +
      optional("--threads", nCoresRequest) +
      conditional(sam, "--sam") +
      conditional(best, "--best") +
      conditional(strata, "--strata") +
      optional("--sam-RG", sam_RG) +
      optional("--seedlen", seedlen) +
      optional("--seedmms", seedmms) +
      optional("-k", k) +
      optional("-m", m) +
      optional("--maxbts", maxbts) +
      optional("--maqerr", maqerr) +
      required(reference) +
      required(R1) +
      optional(R2) +
      " > " + required(output)
  }
}