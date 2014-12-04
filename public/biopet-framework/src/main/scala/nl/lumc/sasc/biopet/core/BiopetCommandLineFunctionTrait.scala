package nl.lumc.sasc.biopet.core

//import java.io.BufferedInputStream
import java.io.File
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.queue.QException
import org.broadinstitute.gatk.queue.function.CommandLineFunction
import org.broadinstitute.gatk.utils.commandline.{ Input, Argument }
//import scala.io.Source
import scala.sys.process.{ Process, ProcessLogger }
import scala.util.matching.Regex
import java.io.FileInputStream
import java.security.MessageDigest

trait BiopetCommandLineFunctionTrait extends CommandLineFunction with Configurable {
  analysisName = configName

  @Input(doc = "deps", required = false)
  var deps: List[File] = Nil

  @Argument(doc = "Threads", required = false)
  var threads = 0
  val defaultThreads = 1

  @Argument(doc = "Vmem", required = false)
  var vmem: String = _
  val defaultVmem: String = ""

  @Argument(doc = "Executable", required = false)
  var executable: String = _

  protected[core] def beforeCmd {
  }

  protected[core] def afterGraph {
  }

  override def freezeFieldValues() {
    checkExecutable
    afterGraph
    jobOutputFile = new File(firstOutput.getParent + "/." + firstOutput.getName + "." + configName + ".out")

    if (threads == 0) threads = getThreads(defaultThreads)
    if (threads > 1) nCoresRequest = Option(threads)

    if (vmem == null) {
      vmem = config("vmem")
      if (vmem == null && !defaultVmem.isEmpty) vmem = defaultVmem
    }
    if (vmem != null) jobResourceRequests :+= "h_vmem=" + vmem
    jobName = configName + ":" + firstOutput.getName

    super.freezeFieldValues()
  }

  protected def checkExecutable {
    if (!BiopetCommandLineFunctionTrait.executableMd5Cache.contains(executable)) {
      try if (executable != null) {
        if (!BiopetCommandLineFunctionTrait.executableCache.contains(executable)) {
          val oldExecutable = executable
          val buffer = new StringBuffer()
          val cmd = Seq("which", executable)
          val process = Process(cmd).run(ProcessLogger(buffer.append(_)))
          if (process.exitValue == 0) {
            executable = buffer.toString
            val file = new File(executable)
            executable = file.getCanonicalPath
          } else {
            logger.error("executable: '" + executable + "' not found, please check config")
            throw new QException("executable: '" + executable + "' not found, please check config")
          }
          BiopetCommandLineFunctionTrait.executableCache += oldExecutable -> executable
          BiopetCommandLineFunctionTrait.executableCache += executable -> executable
        } else {
          executable = BiopetCommandLineFunctionTrait.executableCache(executable)
        }

        if (!BiopetCommandLineFunctionTrait.executableMd5Cache.contains(executable)) {
          val is = new FileInputStream(executable)
          val cnt = is.available
          val bytes = Array.ofDim[Byte](cnt)
          is.read(bytes)
          is.close()
          val temp = MessageDigest.getInstance("MD5").digest(bytes).map("%02X".format(_)).mkString.toLowerCase
          BiopetCommandLineFunctionTrait.executableMd5Cache += executable -> temp
        }

        addJobReportBinding("md5sum_exe", BiopetCommandLineFunctionTrait.executableMd5Cache(executable))
      } catch {
        case ioe: java.io.IOException => logger.warn("Could not use 'which', check on executable skipped: " + ioe)
      }
    } else {
      addJobReportBinding("md5sum_exe", BiopetCommandLineFunctionTrait.executableMd5Cache(executable))
    }
  }

  final protected def preCmdInternal {
    checkExecutable

    beforeCmd

    addJobReportBinding("cores", if (nCoresRequest.get.toInt > 0) nCoresRequest.get.toInt else 1)
    addJobReportBinding("version", getVersion)
  }

  protected def versionCommand: String = null
  protected val versionRegex: Regex = null
  protected val versionExitcode = List(0) // Can select multiple
  private def getVersionInternal: String = {
    if (versionCommand == null || versionRegex == null) return "N/A"
    val stdout = new StringBuffer()
    val stderr = new StringBuffer()
    def outputLog = "Version command: \n" + versionCommand +
      "\n output log: \n stdout: \n" + stdout.toString +
      "\n stderr: \n" + stderr.toString
    val process = Process(versionCommand).run(ProcessLogger(stdout append _ + "\n", stderr append _ + "\n"))
    if (!versionExitcode.contains(process.exitValue)) {
      logger.warn("getVersion give exit code " + process.exitValue + ", version not found \n" + outputLog)
      return "N/A"
    }
    for (line <- stdout.toString.split("\n") ++ stderr.toString.split("\n")) {
      line match {
        case versionRegex(m) => return m
        case _               =>
      }
    }
    logger.warn("getVersion give a exit code " + process.exitValue + " but no version was found, executable correct? \n" + outputLog)
    return "N/A"
  }

  def getVersion: String = {
    if (!BiopetCommandLineFunctionTrait.versionCache.contains(executable))
      BiopetCommandLineFunctionTrait.versionCache += executable -> getVersionInternal
    return BiopetCommandLineFunctionTrait.versionCache(executable)
  }

  def getThreads(default: Int): Int = {
    val maxThreads: Int = config("maxthreads", default = 8)
    val threads: Int = config("threads", default = default)
    if (maxThreads > threads) return threads
    else return maxThreads
  }

  def getThreads(default: Int, module: String): Int = {
    val maxThreads: Int = config("maxthreads", default = 8, submodule = module)
    val threads: Int = config("threads", default = default, submodule = module)
    if (maxThreads > threads) return threads
    else return maxThreads
  }
}

object BiopetCommandLineFunctionTrait {
  import scala.collection.mutable.Map
  private val versionCache: Map[String, String] = Map()
  private val executableMd5Cache: Map[String, String] = Map()
  private val executableCache: Map[String, String] = Map()
}