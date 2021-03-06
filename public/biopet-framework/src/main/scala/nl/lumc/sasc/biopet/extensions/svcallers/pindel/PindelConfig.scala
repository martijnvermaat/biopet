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
package nl.lumc.sasc.biopet.extensions.svcallers.pindel

import nl.lumc.sasc.biopet.core.BiopetJavaCommandLineFunction
import nl.lumc.sasc.biopet.core.PipelineCommand
import nl.lumc.sasc.biopet.core.ToolCommand
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.utils.commandline.{ Input, Output, Argument }
import java.io.File

class PindelConfig(val root: Configurable) extends BiopetJavaCommandLineFunction {
  javaMainClass = getClass.getName
  @Input(doc = "Bam File")
  var input: File = _

  @Output(doc = "Output Config file")
  var output: File = _

  @Argument(doc = "Insertsize")
  var insertsize: Option[Int] = _

  override def commandLine = super.commandLine +
    "-i" + required(input) +
    "-s" + required(insertsize) +
    "-o" + required(output)
}

object PindelConfig extends ToolCommand {
  def apply(root: Configurable, input: File, output: File): PindelConfig = {
    val conf = new PindelConfig(root)
    conf.input = input
    conf.output = output
    return conf
  }

  def apply(root: Configurable, input: File, outputDir: String): PindelConfig = {
    val dir = if (outputDir.endsWith("/")) outputDir else outputDir + "/"
    val outputFile = new File(dir + swapExtension(input.getName))
    return apply(root, input, outputFile)
  }

  def apply(root: Configurable, input: File): PindelConfig = {
    return apply(root, input, new File(swapExtension(input.getAbsolutePath)))
  }

  private def swapExtension(inputFile: String) = inputFile.substring(0, inputFile.lastIndexOf(".bam")) + ".pindel.cfg"

  case class Args(inputbam: File = null, samplelabel: Option[String] = None, insertsize: Option[Int] = None) extends AbstractArgs

  class OptParser extends AbstractOptParser {
    opt[File]('i', "inputbam") required () valueName ("<bamfile/path>") action { (x, c) =>
      c.copy(inputbam = x)
    } text ("Please specify the input bam file")
    opt[String]('l', "samplelabel") valueName ("<sample label>") action { (x, c) =>
      c.copy(samplelabel = Some(x))
    } text ("Sample label is missing")
    opt[Int]('s', "insertsize") valueName ("<insertsize>") action { (x, c) =>
      c.copy(insertsize = Some(x))
    } text ("Insertsize is missing")
  }

  /**
   * @param args the command line arguments
   */
  def main(args: Array[String]): Unit = {
    val argsParser = new OptParser
    val commandArgs: Args = argsParser.parse(args, Args()) getOrElse sys.exit(1)

    val input: File = commandArgs.inputbam

  }
}

