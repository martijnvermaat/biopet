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
/**
 * Due to the license issue with GATK, this part of Biopet can only be used inside the
 * LUMC. Please refer to https://git.lumc.nl/biopet/biopet/wikis/home for instructions
 * on how to use this protected part of biopet or contact us at sasc@lumc.nl
 */
package nl.lumc.sasc.biopet.pipelines.basty

import java.io.File
import nl.lumc.sasc.biopet.core.MultiSampleQScript
import nl.lumc.sasc.biopet.core.PipelineCommand
import nl.lumc.sasc.biopet.extensions.{ RunGubbins, Cat, Raxml }
import nl.lumc.sasc.biopet.pipelines.shiva.{ ShivaTrait, Shiva }
import nl.lumc.sasc.biopet.tools.BastyGenerateFasta
import nl.lumc.sasc.biopet.utils.ConfigUtils

trait BastyTrait extends MultiSampleQScript {
  qscript =>

  case class FastaOutput(variants: File, consensus: File, consensusVariants: File)

  def variantcallers = List("freebayes")

  override def defaults = ConfigUtils.mergeMaps(Map(
    "ploidy" -> 1,
    "variantcallers" -> variantcallers
  ), super.defaults)

  lazy val shiva: ShivaTrait = new Shiva(qscript)

  def summaryFile: File = new File(outputDir, "Sage.summary.json")

  //TODO: Add summary
  def summaryFiles: Map[String, File] = Map()

  //TODO: Add summary
  def summarySettings: Map[String, Any] = Map()

  def makeSample(id: String) = new Sample(id)
  class Sample(sampleId: String) extends AbstractSample(sampleId) {
    //TODO: Add summary
    def summaryFiles: Map[String, File] = Map()

    //TODO: Add summary
    def summaryStats: Map[String, Any] = Map()

    def makeLibrary(id: String) = new Library(id)
    class Library(libId: String) extends AbstractLibrary(libId) {
      //TODO: Add summary
      def summaryFiles: Map[String, File] = Map()

      //TODO: Add summary
      def summaryStats: Map[String, Any] = Map()

      protected def addJobs(): Unit = {}
    }

    var output: FastaOutput = _
    var outputSnps: FastaOutput = _

    protected def addJobs(): Unit = {
      addPerLibJobs()
      output = addGenerateFasta(sampleId, sampleDir)
      outputSnps = addGenerateFasta(sampleId, sampleDir, snpsOnly = true)
    }
  }

  def init() {
    shiva.outputDir = outputDir
    shiva.init
  }

  def biopetScript() {
    shiva.biopetScript
    addAll(shiva.functions)
    addSummaryQScript(shiva)

    addSamplesJobs()
  }

  def addMultiSampleJobs(): Unit = {
    val refVariants = addGenerateFasta(null, new File(outputDir, "fastas" + File.separator + "reference"), outputName = "reference")
    val refVariantSnps = addGenerateFasta(null, new File(outputDir, "fastas" + File.separator + "reference"), outputName = "reference", snpsOnly = true)

    val catVariants = Cat(this, refVariants.variants :: samples.map(_._2.output.variants).toList,
      new File(outputDir, "fastas" + File.separator + "variant.fasta"))
    add(catVariants)
    val catVariantsSnps = Cat(this, refVariantSnps.variants :: samples.map(_._2.outputSnps.variants).toList,
      new File(outputDir, "fastas" + File.separator + "variant.snps_only.fasta"))
    add(catVariantsSnps)

    val catConsensus = Cat(this, refVariants.consensus :: samples.map(_._2.output.consensus).toList,
      new File(outputDir, "fastas" + File.separator + "consensus.fasta"))
    add(catConsensus)
    val catConsensusSnps = Cat(this, refVariantSnps.consensus :: samples.map(_._2.outputSnps.consensus).toList,
      new File(outputDir, "fastas" + File.separator + "consensus.snps_only.fasta"))
    add(catConsensusSnps)

    val catConsensusVariants = Cat(this, refVariants.consensusVariants :: samples.map(_._2.output.consensusVariants).toList,
      new File(outputDir, "fastas" + File.separator + "consensus.variant.fasta"))
    add(catConsensusVariants)
    val catConsensusVariantsSnps = Cat(this, refVariantSnps.consensusVariants :: samples.map(_._2.outputSnps.consensusVariants).toList,
      new File(outputDir, "fastas" + File.separator + "consensus.variant.snps_only.fasta"))
    add(catConsensusVariantsSnps)

    val seed: Int = config("seed", default = 12345)
    def addTreeJobs(variants: File, concensusVariants: File, outputDir: File, outputName: String) {
      val dirSufixRaxml = new File(outputDir, "raxml")
      val dirSufixGubbins = new File(outputDir, "gubbins")

      val raxmlMl = new Raxml(this)
      raxmlMl.input = variants
      raxmlMl.m = config("raxml_ml_model", default = "GTRGAMMAX")
      raxmlMl.p = Some(seed)
      raxmlMl.n = outputName + "_ml"
      raxmlMl.w = dirSufixRaxml
      raxmlMl.N = config("ml_runs", default = 20, submodule = "raxml")
      add(raxmlMl)

      val r = new scala.util.Random(seed)
      val numBoot = config("boot_runs", default = 100, submodule = "raxml").asInt
      val bootList = for (t <- 0 until numBoot) yield {
        val raxmlBoot = new Raxml(this)
        raxmlBoot.threads = 1
        raxmlBoot.input = variants
        raxmlBoot.m = config("raxml_ml_model", default = "GTRGAMMAX")
        raxmlBoot.p = Some(seed)
        raxmlBoot.b = Some(math.abs(r.nextInt))
        raxmlBoot.w = dirSufixRaxml
        raxmlBoot.N = Some(1)
        raxmlBoot.n = outputName + "_boot_" + t
        add(raxmlBoot)
        raxmlBoot.getBootstrapFile.get
      }

      val cat = Cat(this, bootList.toList, new File(outputDir, "/boot_list"))
      add(cat)

      val raxmlBi = new Raxml(this)
      raxmlBi.input = concensusVariants
      raxmlBi.t = raxmlMl.getBestTreeFile
      raxmlBi.z = Some(cat.output)
      raxmlBi.m = config("raxml_ml_model", default = "GTRGAMMAX")
      raxmlBi.p = Some(seed)
      raxmlBi.f = "b"
      raxmlBi.n = outputName + "_bi"
      raxmlBi.w = dirSufixRaxml
      add(raxmlBi)

      val gubbins = new RunGubbins(this)
      gubbins.fastafile = concensusVariants
      gubbins.startingTree = raxmlBi.getBipartitionsFile
      gubbins.outputDirectory = dirSufixGubbins
      add(gubbins)
    }

    addTreeJobs(catVariantsSnps.output, catConsensusVariantsSnps.output,
      new File(outputDir, "trees" + File.separator + "snps_only"), "snps_only")
    addTreeJobs(catVariants.output, catConsensusVariants.output,
      new File(outputDir, "trees" + File.separator + "snps_indels"), "snps_indels")

  }

  def addGenerateFasta(sampleName: String, outputDir: File, outputName: String = null,
                       snpsOnly: Boolean = false): FastaOutput = {
    val bastyGenerateFasta = new BastyGenerateFasta(this)
    bastyGenerateFasta.outputName = if (outputName != null) outputName else sampleName
    bastyGenerateFasta.inputVcf = shiva.variantcalling.get.finalFile
    if (shiva.samples.contains(sampleName)) {
      bastyGenerateFasta.bamFile = shiva.samples(sampleName).preProcessBam.get
    }
    bastyGenerateFasta.outputVariants = new File(outputDir, bastyGenerateFasta.outputName + ".variants" + (if (snpsOnly) ".snps_only" else "") + ".fasta")
    bastyGenerateFasta.outputConsensus = new File(outputDir, bastyGenerateFasta.outputName + ".consensus" + (if (snpsOnly) ".snps_only" else "") + ".fasta")
    bastyGenerateFasta.outputConsensusVariants = new File(outputDir, bastyGenerateFasta.outputName + ".consensus_variants" + (if (snpsOnly) ".snps_only" else "") + ".fasta")
    bastyGenerateFasta.sampleName = sampleName
    bastyGenerateFasta.snpsOnly = snpsOnly
    qscript.add(bastyGenerateFasta)
    return FastaOutput(bastyGenerateFasta.outputVariants, bastyGenerateFasta.outputConsensus, bastyGenerateFasta.outputConsensusVariants)
  }
}
