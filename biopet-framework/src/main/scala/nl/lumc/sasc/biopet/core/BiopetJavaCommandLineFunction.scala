package nl.lumc.sasc.biopet.core

import org.broadinstitute.gatk.queue.function.JavaCommandLineFunction

trait BiopetJavaCommandLineFunction extends JavaCommandLineFunction with BiopetCommandLineFunctionTrait {
  executable = "java"

  override def commandLine: String = {
    preCmdInternal
    val cmd = super.commandLine
    val finalCmd = executable + cmd.substring(cmd.indexOf(" "))
    //    addJobReportBinding("command", cmd)
    return cmd
  }
}
