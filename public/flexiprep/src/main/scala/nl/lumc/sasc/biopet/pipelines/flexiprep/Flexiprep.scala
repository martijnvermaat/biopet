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
package nl.lumc.sasc.biopet.pipelines.flexiprep

import org.broadinstitute.gatk.queue.QScript
import org.broadinstitute.gatk.utils.commandline.{ Input, Argument }

import nl.lumc.sasc.biopet.core.{ BiopetQScript, PipelineCommand }
import nl.lumc.sasc.biopet.core.config.Configurable
import nl.lumc.sasc.biopet.extensions.{ Gzip, Pbzip2, Md5sum, Zcat, Seqstat }
import nl.lumc.sasc.biopet.tools.FastqSync

class Flexiprep(val root: Configurable) extends QScript with BiopetQScript {
  def this() = this(null)

  @Input(doc = "R1 fastq file (gzipped allowed)", shortName = "R1", required = true)
  var input_R1: File = _

  @Input(doc = "R2 fastq file (gzipped allowed)", shortName = "R2", required = false)
  var input_R2: Option[File] = None

  /** Skip Trim fastq files */
  var skipTrim: Boolean = config("skip_trim", default = false)

  /** Skip Clip fastq files */
  var skipClip: Boolean = config("skip_clip", default = false)

  // TODO: hide sampleId and libId from the command line so they do not interfere with our config values

  /** Sample name */
  @Argument(doc = "Sample ID", shortName = "sample", required = true)
  var sampleId: String = _

  /** Library name */
  @Argument(doc = "Library ID", shortName = "library", required = true)
  var libId: String = _

  var paired: Boolean = input_R2.isDefined
  var R1_ext: String = _
  var R2_ext: String = _
  var R1_name: String = _
  var R2_name: String = _

  var fastqc_R1: Fastqc = _
  var fastqc_R2: Fastqc = _
  var fastqc_R1_after: Fastqc = _
  var fastqc_R2_after: Fastqc = _

  val summary = new FlexiprepSummary(this)

  def init() {
    require(outputDir != null, "Missing output directory on flexiprep module")
    require(input_R1 != null, "Missing input R1 on flexiprep module")
    require(sampleId != null, "Missing sample ID on flexiprep module")
    require(libId != null, "Missing library ID on flexiprep module")

    paired = input_R2.isDefined

    if (input_R1.endsWith(".gz")) R1_name = input_R1.getName.substring(0, input_R1.getName.lastIndexOf(".gz"))
    else if (input_R1.endsWith(".gzip")) R1_name = input_R1.getName.substring(0, input_R1.getName.lastIndexOf(".gzip"))
    else R1_name = input_R1.getName
    R1_ext = R1_name.substring(R1_name.lastIndexOf("."), R1_name.size)
    R1_name = R1_name.substring(0, R1_name.lastIndexOf(R1_ext))

    input_R2 match {
      case Some(fileR2) => {
        paired = true
        if (fileR2.endsWith(".gz")) R2_name = fileR2.getName.substring(0, fileR2.getName.lastIndexOf(".gz"))
        else if (fileR2.endsWith(".gzip")) R2_name = fileR2.getName.substring(0, fileR2.getName.lastIndexOf(".gzip"))
        else R2_name = fileR2.getName
        R2_ext = R2_name.substring(R2_name.lastIndexOf("."), R2_name.size)
        R2_name = R2_name.substring(0, R2_name.lastIndexOf(R2_ext))
      }
      case _ =>
    }

    summary.out = outputDir + sampleId + "-" + libId + ".qc.summary.json"
  }

  def biopetScript() {
    runInitialJobs()

    val out = if (paired) runTrimClip(outputFiles("fastq_input_R1"), outputFiles("fastq_input_R2"), outputDir)
    else runTrimClip(outputFiles("fastq_input_R1"), outputDir)

    val R1_files = for ((k, v) <- outputFiles if k.endsWith("output_R1")) yield v
    val R2_files = for ((k, v) <- outputFiles if k.endsWith("output_R2")) yield v
    runFinalize(R1_files.toList, R2_files.toList)
  }

  def runInitialJobs() {
    outputFiles += ("fastq_input_R1" -> extractIfNeeded(input_R1, outputDir))
    if (paired) outputFiles += ("fastq_input_R2" -> extractIfNeeded(input_R2.get, outputDir))

    fastqc_R1 = Fastqc(this, input_R1, outputDir + "/" + R1_name + ".fastqc/")
    add(fastqc_R1)
    summary.addFastqc(fastqc_R1)
    outputFiles += ("fastqc_R1" -> fastqc_R1.output)

    val md5sum_R1 = Md5sum(this, input_R1, outputDir)
    add(md5sum_R1)
    summary.addMd5sum(md5sum_R1, R2 = false, after = false)

    if (paired) {
      fastqc_R2 = Fastqc(this, input_R2.get, outputDir + "/" + R2_name + ".fastqc/")
      add(fastqc_R2)
      summary.addFastqc(fastqc_R2, R2 = true)
      outputFiles += ("fastqc_R2" -> fastqc_R2.output)

      val md5sum_R2 = Md5sum(this, input_R2.get, outputDir)
      add(md5sum_R2)
      summary.addMd5sum(md5sum_R2, R2 = true, after = false)
    }
  }

  def runTrimClip(R1_in: File, outDir: String, chunk: String): (File, File, List[File]) = {
    runTrimClip(R1_in, new File(""), outDir, chunk)
  }
  def runTrimClip(R1_in: File, outDir: String): (File, File, List[File]) = {
    runTrimClip(R1_in, new File(""), outDir, "")
  }
  def runTrimClip(R1_in: File, R2_in: File, outDir: String): (File, File, List[File]) = {
    runTrimClip(R1_in, R2_in, outDir, "")
  }
  def runTrimClip(R1_in: File, R2_in: File, outDir: String, chunkarg: String): (File, File, List[File]) = {
    val chunk = if (chunkarg.isEmpty || chunkarg.endsWith("_")) chunkarg else chunkarg + "_"
    var results: Map[String, File] = Map()

    var R1: File = new File(R1_in)
    var R2: File = if (paired) new File(R2_in) else null
    var deps: List[File] = if (paired) List(R1, R2) else List(R1)

    val seqtkSeq_R1 = SeqtkSeq(this, R1, swapExt(outDir, R1, R1_ext, ".sanger" + R1_ext), fastqc_R1)
    seqtkSeq_R1.isIntermediate = true
    add(seqtkSeq_R1)
    R1 = seqtkSeq_R1.output
    deps ::= R1

    if (paired) {
      val seqtkSeq_R2 = SeqtkSeq(this, R2, swapExt(outDir, R2, R2_ext, ".sanger" + R2_ext), fastqc_R2)
      seqtkSeq_R2.isIntermediate = true
      add(seqtkSeq_R2)
      R2 = seqtkSeq_R2.output
      deps ::= R2
    }

    val seqstat_R1 = Seqstat(this, R1, outDir)
    seqstat_R1.isIntermediate = true
    add(seqstat_R1)
    summary.addSeqstat(seqstat_R1, R2 = false, after = false, chunk)

    if (paired) {
      val seqstat_R2 = Seqstat(this, R2, outDir)
      seqstat_R2.isIntermediate = true
      add(seqstat_R2)
      summary.addSeqstat(seqstat_R2, R2 = true, after = false, chunk)
    }

    if (!skipClip) { // Adapter clipping

      val cutadapt_R1 = Cutadapt(this, R1, swapExt(outDir, R1, R1_ext, ".clip" + R1_ext))
      cutadapt_R1.fastqc = fastqc_R1
      cutadapt_R1.isIntermediate = true
      add(cutadapt_R1)
      summary.addCutadapt(cutadapt_R1, R2 = false, chunk)
      R1 = cutadapt_R1.fastq_output
      deps ::= R1
      outputFiles += ("cutadapt_R1_stats" -> cutadapt_R1.stats_output)

      if (paired) {
        val cutadapt_R2 = Cutadapt(this, R2, swapExt(outDir, R2, R2_ext, ".clip" + R2_ext))
        outputFiles += ("cutadapt_R2_stats" -> cutadapt_R2.stats_output)
        cutadapt_R2.fastqc = fastqc_R2
        cutadapt_R2.isIntermediate = true
        add(cutadapt_R2)
        summary.addCutadapt(cutadapt_R2, R2 = true, chunk)
        R2 = cutadapt_R2.fastq_output
        deps ::= R2

        val fqSync = new FastqSync(this)
        fqSync.refFastq = cutadapt_R1.fastq_input
        fqSync.inputFastq1 = cutadapt_R1.fastq_output
        fqSync.inputFastq2 = cutadapt_R2.fastq_output
        fqSync.outputFastq1 = swapExt(outDir, R1, R1_ext, ".sync" + R1_ext)
        fqSync.outputFastq2 = swapExt(outDir, R2, R2_ext, ".sync" + R2_ext)
        fqSync.outputStats = swapExt(outDir, R1, R1_ext, ".sync.stats")
        fqSync.deps :::= deps
        add(fqSync)

        summary.addFastqcSync(fqSync, chunk)
        outputFiles += ("syncStats" -> fqSync.outputStats)
        R1 = fqSync.outputFastq1
        R2 = fqSync.outputFastq2
        deps :::= R1 :: R2 :: Nil
      }
    }

    if (!skipTrim) { // Quality trimming
      val sickle = new Sickle(this)
      sickle.input_R1 = R1
      sickle.output_R1 = swapExt(outDir, R1, R1_ext, ".trim" + R1_ext)
      if (paired) {
        sickle.input_R2 = R2
        sickle.output_R2 = swapExt(outDir, R2, R2_ext, ".trim" + R2_ext)
        sickle.output_singles = swapExt(outDir, R2, R2_ext, ".trim.singles" + R1_ext)
      }
      sickle.output_stats = swapExt(outDir, R1, R1_ext, ".trim.stats")
      sickle.deps = deps
      sickle.isIntermediate = true
      add(sickle)
      summary.addSickle(sickle, chunk)
      R1 = sickle.output_R1
      if (paired) R2 = sickle.output_R2
    }

    val seqstat_R1_after = Seqstat(this, R1, outDir)
    seqstat_R1_after.deps = deps
    add(seqstat_R1_after)
    summary.addSeqstat(seqstat_R1_after, R2 = false, after = true, chunk)

    if (paired) {
      val seqstat_R2_after = Seqstat(this, R2, outDir)
      seqstat_R2_after.deps = deps
      add(seqstat_R2_after)
      summary.addSeqstat(seqstat_R2_after, R2 = true, after = true, chunk)
    }

    outputFiles += (chunk + "output_R1" -> R1)
    if (paired) outputFiles += (chunk + "output_R2" -> R2)
    return (R1, R2, deps)
  }

  def runFinalize(fastq_R1: List[File], fastq_R2: List[File]) {
    if (fastq_R1.length != fastq_R2.length && paired) throw new IllegalStateException("R1 and R2 file number is not the same")
    val R1 = new File(outputDir + R1_name + ".qc" + R1_ext + ".gz")
    val R2 = new File(outputDir + R2_name + ".qc" + R2_ext + ".gz")

    add(Gzip(this, fastq_R1, R1))
    if (paired) add(Gzip(this, fastq_R2, R2))

    outputFiles += ("output_R1_gzip" -> R1)
    if (paired) outputFiles += ("output_R2_gzip" -> R2)

    if (!skipTrim || !skipClip) {
      val md5sum_R1 = Md5sum(this, R1, outputDir)
      add(md5sum_R1)
      summary.addMd5sum(md5sum_R1, R2 = false, after = true)
      if (paired) {
        val md5sum_R2 = Md5sum(this, R2, outputDir)
        add(md5sum_R2)
        summary.addMd5sum(md5sum_R2, R2 = true, after = true)
      }
      fastqc_R1_after = Fastqc(this, R1, outputDir + "/" + R1_name + ".qc.fastqc/")
      add(fastqc_R1_after)
      summary.addFastqc(fastqc_R1_after, after = true)
      if (paired) {
        fastqc_R2_after = Fastqc(this, R2, outputDir + "/" + R2_name + ".qc.fastqc/")
        add(fastqc_R2_after)
        summary.addFastqc(fastqc_R2_after, R2 = true, after = true)
      }
    }

    add(summary)
  }

  def extractIfNeeded(file: File, runDir: String): File = {
    if (file == null) return file
    else if (file.getName().endsWith(".gz") || file.getName().endsWith(".gzip")) {
      var newFile: File = swapExt(runDir, file, ".gz", "")
      if (file.getName().endsWith(".gzip")) newFile = swapExt(runDir, file, ".gzip", "")
      val zcatCommand = Zcat(this, file, newFile)
      zcatCommand.isIntermediate = true
      add(zcatCommand)
      return newFile
    } else if (file.getName().endsWith(".bz2")) {
      val newFile = swapExt(runDir, file, ".bz2", "")
      val pbzip2 = Pbzip2(this, file, newFile)
      pbzip2.isIntermediate = true
      add(pbzip2)
      return newFile
    } else return file
  }
}

object Flexiprep extends PipelineCommand
