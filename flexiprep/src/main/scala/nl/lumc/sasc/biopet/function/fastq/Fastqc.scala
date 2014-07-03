package nl.lumc.sasc.biopet.function.fastq

import nl.lumc.sasc.biopet.core._
import nl.lumc.sasc.biopet.core.config._
import org.broadinstitute.sting.commandline._
import java.io.File
import scala.sys.process._

class Fastqc(val root:Configurable) extends BiopetCommandLineFunction {
  
  @Input(doc="Contaminants", required=false)
  var contaminants: File = _
  
  @Input(doc="Fastq file", shortName="FQ")
  var fastqfile: File = _
  
  @Output(doc="Output", shortName="out")
  var output: File = _
  
  executeble = config("exe","fastqc")
  var java_exe: String = config("exe", "java", "java")
  var kmers: Int = config("kmers", 5)
  var quiet: Boolean = config("quiet", false)
  var noextract: Boolean = config("noextract", false)
  var nogroup: Boolean = config("nogroup", false)
    
  override val versionRegex = """FastQC (.*)""".r
  override val defaultThreads = 4
  
  override def afterGraph {
    this.checkExecuteble
    val fastqcDir = executeble.substring(0, executeble.lastIndexOf("/"))
    if (contaminants == null) contaminants = new File(fastqcDir + "/Contaminants/contaminant_list.txt")
  }
  
  override def versionCommand = executeble + " --version"
  
  def cmdLine = {
    required(executeble) + 
      optional("--java", java_exe) +
      optional("--threads",threads) +
      optional("--contaminants",contaminants) +
      optional("--kmers",kmers) +
      conditional(nogroup, "--nogroup") +
      conditional(noextract, "--noextract") +
      conditional(quiet, "--quiet") +
      required("-o",output.getParent()) + 
      required(fastqfile) + 
      required(" > ", output, escape=false)
  }
}