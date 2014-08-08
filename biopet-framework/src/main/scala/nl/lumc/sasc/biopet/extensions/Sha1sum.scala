package nl.lumc.sasc.biopet.extensions

import nl.lumc.sasc.biopet.core.BiopetCommandLineFunction
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.utils.commandline._
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
    val ext = fastqfile.getName.substring(fastqfile.getName.lastIndexOf("."))
    sha1sum.input = fastqfile
    sha1sum.output = new File(outDir + fastqfile.getName.substring(0, fastqfile.getName.lastIndexOf(".")) + ".sha1")
    return sha1sum
  }
}
