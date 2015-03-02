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
package nl.lumc.sasc.biopet.pipelines.gentrap

import org.broadinstitute.gatk.queue.QScript
import picard.analysis.directed.RnaSeqMetricsCollector.StrandSpecificity

import nl.lumc.sasc.biopet.core._
import nl.lumc.sasc.biopet.core.config._
import nl.lumc.sasc.biopet.core.summary._
import nl.lumc.sasc.biopet.extensions.{ Cufflinks, HtseqCount, Ln }
import nl.lumc.sasc.biopet.extensions.picard.{ CollectRnaSeqMetrics, MergeSamFiles, SortSam }
import nl.lumc.sasc.biopet.pipelines.mapping.Mapping
import nl.lumc.sasc.biopet.utils.ConfigUtils
import nl.lumc.sasc.biopet.tools.MergeTables

/**
 * Gentrap pipeline
 * Generic transcriptome analysis pipeline
 */
class Gentrap(val root: Configurable) extends QScript with MultiSampleQScript with SummaryQScript { qscript =>

  import Gentrap._
  import Gentrap.ExpMeasures._
  import Gentrap.StrandProtocol._

  // alternative constructor for initialization with empty configuration
  def this() = this(null)

  /** Split aligner to use */
  var aligner: String = config("aligner", default = "gsnap")

  /** Expression measurement modes */
  // see the enumeration below for valid modes
  var expressionMeasures: List[String] = config("expression_measures", default = Nil)

  /** Strandedness modes */
  var strandProtocol: String = config("strand_protocol")

  /** GTF reference file */
  var annotationGtf: Option[File] = config("annotation_gtf")

  /** BED reference file */
  var annotationBed: Option[File] = config("annotation_bed")

  /** refFlat reference file */
  var annotationRefFlat: File = config("annotation_refflat")

  /*
  /** Variant calling */
  @Argument(doc = "Variant caller", fullName = "variant_caller", shortName = "varCaller", required = false, validation = "varscan|snvmix")
  var varcaller: String = _
  */

  /** Default pipeline config */
  override def defaults = ConfigUtils.mergeMaps(
    Map(
      "gsnap" -> Map(
        "novelsplicing" -> 1,
        "batch" -> 4,
        "format" -> "sam"
      )
    ), super.defaults)

  /** Private container for expression modes */
  private val expMeasures: Set[ExpMeasures.Value] = expressionMeasures
    .map { makeExpMeasure }.toSet

  /** Private value for strand protocol */
  private val strProtocol: StrandProtocol.Value = makeStrandProtocol(strandProtocol)

  /** Output summary file */
  def summaryFile: File = new File(outputDir, "gentrap.summary.json")

  /** Files that will be listed in the summary file */
  def summaryFiles: Map[String, File] = Map()

  /** Statistics shown in the summary file */
  def summaryStats: Map[String, Any] = Map()

  /** Pipeline settings shown in the summary file */
  def summarySettings: Map[String, Any] = Map(
    "aligner" -> aligner,
    "expression_measures" -> expressionMeasures,
    "strand_protocol" -> strandProtocol,
    "annotation_refflat" -> annotationRefFlat
  ) ++ Map(
      "annotation_gtf" -> annotationGtf,
      "annotation_bed" -> annotationBed
    ).collect { case (key, Some(value)) => key -> value }

  /** Steps to run before biopetScript */
  def init(): Unit = {
    // TODO: validate that exons are flattened or not (depending on another option flag?)
    // validate required annotation files
    if (expMeasures.contains(FragmentsPerGene))
      require(annotationGtf.isDefined, "GTF file must be defined for counting fragments per gene")

    if (expMeasures.contains(FragmentsPerExon))
      // TODO: validate that GTF file contains exon features
      require(annotationGtf.isDefined, "GTF file must be defined for counting fragments per exon")

    if (expMeasures.contains(BasesPerGene))
      require(annotationBed.isDefined, "BED file must be defined for counting bases per gene")

    if (expMeasures.contains(BasesPerExon))
      require(annotationBed.isDefined, "BED file must be defined for counting bases per exon")

    if (expMeasures.contains(CufflinksBlind) || expMeasures.contains(CufflinksGuided) || expMeasures.contains(CufflinksStrict))
      require(annotationGtf.isDefined, "GTF file must be defined for Cufflinks-based modes")
  }

  def biopetScript(): Unit = {
    addSamplesJobs()
  }

  def addMultiSampleJobs(): Unit = {

    /** Adds output merge jobs for the given expression mode */
    // TODO: can we combine the enum with the file extension (to reduce duplication and potential errors)
    def addMergeTableJob(expMode: ExpMeasures.Value, inFunc: (Sample => Option[File]), ext: String,
                         idCols: List[Int], valCol: Int, outName: String): Unit =
      if (expMeasures.contains(expMode)) {
        val job = new MergeTables(qscript)
        job.inputTables = samples.values.map { inFunc }.toList.flatten
        job.output = new File(outputDir, outName)
        job.idColumnIndices = idCols.map(_.toString)
        job.valueColumnIndex = valCol
        job.fileExtension = Option(ext)
        add(job)
      }

    addMergeTableJob(FragmentsPerGene, (s: Sample) => s.geneFragmentsCount, ".fragments_per_gene", List(1), 2,
      "all_samples.fragments_per_gene")
    addMergeTableJob(FragmentsPerExon, (s: Sample) => s.exonFragmentsCount, ".fragments_per_exon", List(1), 2,
      "all_samples.fragments_per_exon")

    // TODO: use proper notation
    addSummaryJobs
  }

  def makeSample(sampleId: String): Sample = new Sample(sampleId)

  class Sample(sampleId: String) extends AbstractSample(sampleId) {

    /** Sample output directory */
    override def sampleDir: File = new File(outputDir, "sample_" + sampleId)

    /** Summary stats of the sample */
    def summaryStats: Map[String, Any] = Map()

    /** Summary files of the sample */
    def summaryFiles: Map[String, File] = Map(
      "alignment" -> alnFile,
      "metrics" -> collectRnaSeqMetricsJob.output
    ) ++ Map(
        "gene_fragments_count" -> geneFragmentsCount,
        "exon_fragments_count" -> exonFragmentsCount,
        "gene_fpkm_cufflinks_strict" -> geneFpkmCufflinksStrict,
        "gene_fpkm_cufflinks_guided" -> geneFpkmCufflinksGuided,
        "gene_fpkm_cufflinks_blind" -> geneFpkmCufflinksBlind
      ).collect { case (key, Some(value)) => key -> value }

    /** Per-sample alignment file */
    def alnFile: File = createFile(".bam")

    /** Read count per gene file */
    def geneFragmentsCount: Option[File] = fragmentsPerGeneJob
      .collect { case job => job.output }

    /** Read count per exon file */
    def exonFragmentsCount: Option[File] = fragmentsPerExonJob
      .collect { case job => job.output }

    /** Gene tracking file from Cufflinks strict mode */
    def geneFpkmCufflinksStrict: Option[File] = cufflinksStrictJob
      .collect { case job => job.outputGenesFpkm }

    /** Isoforms tracking file from Cufflinks strict mode */
    def isoformFpkmCufflinksStrict: Option[File] = cufflinksStrictJob
      .collect { case job => job.outputIsoformsFpkm }

    /** Gene tracking file from Cufflinks guided mode */
    def geneFpkmCufflinksGuided: Option[File] = cufflinksGuidedJob
      .collect { case job => job.outputGenesFpkm }

    /** Isoforms tracking file from Cufflinks guided mode */
    def isoformFpkmCufflinksGuided: Option[File] = cufflinksGuidedJob
      .collect { case job => job.outputIsoformsFpkm }

    /** Gene tracking file from Cufflinks guided mode */
    def geneFpkmCufflinksBlind: Option[File] = cufflinksBlindJob
      .collect { case job => job.outputGenesFpkm }

    /** Isoforms tracking file from Cufflinks blind mode */
    def isoformFpkmCufflinksBlind: Option[File] = cufflinksBlindJob
      .collect { case job => job.outputIsoformsFpkm }

    /** ID-sorting job for HTseq-count jobs */
    private def idSortingJob: Option[SortSam] = (expMeasures.contains(FragmentsPerExon) || expMeasures.contains(FragmentsPerGene))
      .option {
        val job = new SortSam(qscript)
        job.input = alnFile
        job.output = createFile(".idsorted.bam")
        job.sortOrder = "queryname"
        job
      }

    /** Read counting job per gene */
    private def fragmentsPerGeneJob: Option[HtseqCount] = expMeasures
      .contains(FragmentsPerGene)
      .option {
        require(idSortingJob.nonEmpty)
        val job = new HtseqCount(qscript)
        job.inputAnnotation = annotationGtf.get
        job.inputAlignment = idSortingJob.get.output
        job.output = createFile(".fragments_per_gene")
        job.format = Option("bam")
        job.order = Option("name")
        job.stranded = strProtocol match {
          case NonSpecific => Option("no")
          case Dutp        => Option("reverse")
          case _           => throw new IllegalStateException
        }
        job
      }

    /** Read counting job per exon */
    private def fragmentsPerExonJob: Option[HtseqCount] = expMeasures
      .contains(FragmentsPerExon)
      .option {
        require(idSortingJob.nonEmpty)
        val job = new HtseqCount(qscript)
        job.inputAnnotation = annotationGtf.get
        job.inputAlignment = idSortingJob.get.output
        job.output = createFile(".fragments_per_exon")
        job.format = Option("bam")
        job.order = Option("name")
        job.stranded = strProtocol match {
          case NonSpecific => Option("no")
          case Dutp        => Option("reverse")
          case _           => throw new IllegalStateException
        }
        // TODO: ensure that the "exon_id" attributes exist for all exons in the GTF
        job.idattr = Option("exon_id")
        job
      }

    /** Cufflinks strict job */
    private def cufflinksStrictJob: Option[Cufflinks] = expMeasures
      .contains(CufflinksStrict)
      .option {
        val job = new Cufflinks(qscript) {
          override def configName = "cufflinks"
          override def configPath: List[String] = super.configPath ::: "cufflinks_strict" :: Nil
        }
        job.input = alnFile
        job.GTF = annotationGtf
        job.GTF_guide = None
        job.output_dir = new File(sampleDir, "cufflinks_strict")
        job
      }

    /** Cufflinks guided job */
    private def cufflinksGuidedJob: Option[Cufflinks] = expMeasures
      .contains(CufflinksStrict)
      .option {
        val job = new Cufflinks(qscript) {
          override def configName = "cufflinks"
          override def configPath: List[String] = super.configPath ::: "cufflinks_guided" :: Nil
        }
        job.input = alnFile
        job.GTF = None
        job.GTF_guide = annotationGtf
        job.output_dir = new File(sampleDir, "cufflinks_guided")
        job
      }

    /** Cufflinks blind job */
    private def cufflinksBlindJob: Option[Cufflinks] = expMeasures
      .contains(CufflinksStrict)
      .option {
        val job = new Cufflinks(qscript) {
          override def configName = "cufflinks"
          override def configPath: List[String] = super.configPath ::: "cufflinks_blind" :: Nil
        }
        job.input = alnFile
        job.GTF = None
        job.GTF_guide = None
        job.output_dir = new File(sampleDir, "cufflinks_blind")
        job
      }

    /** Picard CollectRnaSeqMetrics job */
    private def collectRnaSeqMetricsJob: CollectRnaSeqMetrics = {
      val job = new CollectRnaSeqMetrics(qscript)
      job.input = alnFile
      job.output = createFile(".rna_metrics")
      job.refFlat = annotationRefFlat
      job.chartOutput = Option(createFile(".coverage_bias.pdf"))
      job.assumeSorted = true
      job.strandSpecificity = strProtocol match {
        case NonSpecific => Option(StrandSpecificity.NONE.toString)
        case Dutp        => Option(StrandSpecificity.SECOND_READ_TRANSCRIPTION_STRAND.toString)
        case _           => throw new IllegalStateException
      }
      job
    }

    // TODO: add warnings or other messages for config values that are hard-coded by the pipeline
    def addJobs(): Unit = {
      // add per-library jobs
      addPerLibJobs()
      // merge or symlink per-library alignments
      addSampleAlnJob()
      // general RNA-seq metrics
      add(collectRnaSeqMetricsJob)
      // measure expression depending on modes set in expMeasures
      idSortingJob.foreach(add(_))
      fragmentsPerGeneJob.foreach(add(_))
      fragmentsPerExonJob.foreach(add(_))
      cufflinksStrictJob.foreach(add(_))
      cufflinksGuidedJob.foreach(add(_))
      cufflinksBlindJob.foreach(add(_))
    }

    private def addSampleAlnJob(): Unit = libraries.values.map(_.alnFile).toList match {
      // library only has one file, then we symlink
      case file :: Nil =>
        val ln = new Ln(qscript)
        ln.in = file
        ln.out = alnFile
        add(ln)
      // library has multiple files, then we merge
      case files @ f :: fs =>
        val merge = new MergeSamFiles(qscript)
        merge.input = files
        merge.sortOrder = "coordinate"
        merge.output = alnFile
        add(merge)
      // library has 0 or less files, error!
      case Nil => throw new IllegalStateException("Per-library alignment files nonexistent.")
    }

    /** Add jobs for fragments per gene counting using HTSeq */
    // We are forcing the sort order to be ID-sorted, since HTSeq-count often chokes when using position-sorting due
    // to its buffer not being large enough.

    def makeLibrary(libId: String): Library = new Library(libId)

    class Library(libId: String) extends AbstractLibrary(libId) {

      /** Summary stats of the library */
      def summaryStats: Map[String, Any] = Map()

      /** Summary files of the library */
      def summaryFiles: Map[String, File] = Map(
        "alignment" -> mappingJob.outputFiles("finalBamFile")
      )

      /** Alignment results of this library ~ can only be accessed after addJobs is run! */
      def alnFile: File = mappingJob.outputFiles("finalBamFile")

      /** Per-library mapping job */
      def mappingJob: Mapping = {
        val job = new Mapping(qscript)
        job.sampleId = Option(sampleId)
        job.libId = Option(libId)
        job.outputDir = libDir
        job.input_R1 = config("R1")
        job.input_R2 = config("R2")
        job.init()
        job.biopetScript()
        job
      }

      def addJobs(): Unit = {
        // create per-library alignment file
        addAll(mappingJob.functions)
        qscript.addSummaryQScript(mappingJob)
      }

    }
  }
}

object Gentrap extends PipelineCommand {

  /** implicit extension that allows to create option values based on boolean values */
  implicit class RichBoolean(val b: Boolean) extends AnyVal {
    final def option[A](a: => A): Option[A] = if (b) Some(a) else None
  }

  /** Enumeration of available expression measures */
  object ExpMeasures extends Enumeration {
    val FragmentsPerGene, FragmentsPerExon, BasesPerGene, BasesPerExon, CufflinksStrict, CufflinksGuided, CufflinksBlind, Cuffquant, Rsem = Value
  }

  /** Enumeration of available strandedness */
  object StrandProtocol extends Enumeration {
    // for now, only non-strand specific and dUTP stranded protocol is supported
    // TODO: other strandedness protocol?
    val NonSpecific, Dutp = Value
  }

  /** Converts string with underscores into camel-case strings */
  private def camelize(ustring: String): String = ustring
    .split("_")
    .map(_.toLowerCase.capitalize)
    .mkString("")

  /** Conversion from raw user-supplied expression measure string to enum value */
  private def makeExpMeasure(rawName: String): ExpMeasures.Value = {
    try {
      ExpMeasures.withName(camelize(rawName))
    } catch {
      case nse: NoSuchElementException => throw new IllegalArgumentException("Invalid expression measure: " + rawName)
      case e: Exception                => throw e
    }
  }

  /** Conversion from raw user-supplied expression measure string to enum value */
  private def makeStrandProtocol(rawName: String): StrandProtocol.Value = {
    try {
      StrandProtocol.withName(camelize(rawName))
    } catch {
      case nse: NoSuchElementException => throw new IllegalArgumentException("Invalid strand protocol: " + rawName)
      case e: Exception                => throw e
    }
  }
}