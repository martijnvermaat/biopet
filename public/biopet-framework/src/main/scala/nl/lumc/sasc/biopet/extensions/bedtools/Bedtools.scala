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
package nl.lumc.sasc.biopet.extensions.bedtools

import nl.lumc.sasc.biopet.core.BiopetCommandLineFunction

/** General abstract class for bedtools extensions */
abstract class Bedtools extends BiopetCommandLineFunction {
  override def subPath = "bedtools" :: super.subPath
  executable = config("exe", default = "bedtools", submodule = "bedtools")
  override def versionCommand = executable + " --version"
  override val versionRegex = """bedtools (.*)""".r
}