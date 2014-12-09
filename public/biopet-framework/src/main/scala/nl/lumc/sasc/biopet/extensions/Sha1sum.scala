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

import nl.lumc.sasc.biopet.core.BiopetCommandLineFunction
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.utils.commandline.{ Input, Output }
import java.io.File
import argonaut._, Argonaut._
import scalaz._, Scalaz._
import scala.io.Source

class Sha1sum(val root: Configurable) extends BiopetCommandLineFunction {
  @Input(doc = "Input file")
  var input: File = _

  @Output(doc = "File to write input file checksum")
  var output: File = _

  executable = config("exe", default = "sha1sum")

  def cmdLine = required(executable) + required(input) + " > " + required(output)

  def getSummary: Json = {
    val data = Source.fromFile(output).mkString.split(" ")
    return ("path" := output.getAbsolutePath) ->:
      ("sha1sum" := data(0)) ->:
      jEmptyObject
  }
}

object Sha1sum {
  def apply(root: Configurable, fastqfile: File, outDir: String): Sha1sum = {
    val sha1sum = new Sha1sum(root)
    sha1sum.input = fastqfile
    sha1sum.output = new File(outDir + fastqfile.getName + ".sha1")
    return sha1sum
  }
}
