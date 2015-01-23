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
package nl.lumc.sasc.biopet.extensions.conifer

import java.io.File

import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.utils.commandline.{Output, Input}

class ConiferRPKM(val root: Configurable) extends Conifer {

  @Input(doc = "Bam file", required = true)
  var bamFile: File = _

  @Input(doc = "Probes / capture kit definition as bed file: chr,start,stop,gene-annot", required = true)
  var probes: File = _

  @Output(doc = "Output RPKM.txt", shortName = "out")
  var output: File = _

  private var config_file: File = _

  override def afterGraph {
    this.checkExecutable
  }

  def cmdLine = required(executable) +
    required("rpkm")+
    " --probes" + required(probes) +
    " --input" + required(bamFile) +
    " --output" + required(output)
}
