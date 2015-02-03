/**
 * Due to the license issue with GATK, this part of Biopet can only be used inside the
 * LUMC. Please refer to https://git.lumc.nl/biopet/biopet/wikis/home for instructions
 * on how to use this protected part of biopet or contact us at sasc@lumc.nl
 */
package nl.lumc.sasc.biopet.pipelines.gatk

import nl.lumc.sasc.biopet.core.MultiSampleQScript
import nl.lumc.sasc.biopet.core.PipelineCommand
import nl.lumc.sasc.biopet.core.config.Configurable
import htsjdk.samtools.SamReaderFactory
import scala.collection.JavaConversions._
import nl.lumc.sasc.biopet.extensions.gatk.{ CombineVariants, CombineGVCFs }
import nl.lumc.sasc.biopet.extensions.picard.AddOrReplaceReadGroups
import nl.lumc.sasc.biopet.extensions.picard.SamToFastq
import nl.lumc.sasc.biopet.pipelines.bammetrics.BamMetrics
import nl.lumc.sasc.biopet.pipelines.mapping.Mapping
import org.broadinstitute.gatk.queue.QScript
import org.broadinstitute.gatk.utils.commandline.{ Argument }

class GatkPipeline(val root: Configurable) extends QScript with MultiSampleQScript {
  qscript =>
  def this() = this(null)

  @Argument(doc = "Skip Genotyping step", shortName = "skipgenotyping", required = false)
  var skipGenotyping: Boolean = config("skip_genotyping", default = false)

  /** Merge gvcfs */
  var mergeGvcfs: Boolean = config("merge_gvcfs", default = false)

  /** Joint variantcalling */
  var jointVariantcalling: Boolean = config("joint_variantcalling", default = false)

  /** Joint genotyping */
  var jointGenotyping: Boolean = config("joint_genotyping", default = false)

  var singleSampleCalling = config("single_sample_calling", default = true)
  var reference: File = config("reference", required = true)
  var dbsnp: File = config("dbsnp")
  var useAllelesOption: Boolean = config("use_alleles_option", default = false)
  val externalGvcfs = config("external_gvcfs_files", default = Nil).asFileList

  def makeSample(id: String) = new Sample(id)
  class Sample(sampleId: String) extends AbstractSample(sampleId) {
    def makeLibrary(id: String) = new Library(id)
    class Library(libraryId: String) extends AbstractLibrary(libraryId) {
      val mapping = new Mapping(qscript)
      mapping.sampleId = sampleId
      mapping.libraryId = libraryId
      mapping.outputDir = libDir + "/variantcalling/"

      /** Library variantcalling */
      val gatkVariantcalling = new GatkVariantcalling(qscript)
      gatkVariantcalling.sampleID = sampleId
      gatkVariantcalling.outputDir = libDir

      protected def addJobs(): Unit = {
        val bamFile: Option[File] = if (config.contains("R1")) {
          mapping.input_R1 = config("R1")
          mapping.input_R2 = config("R2")
          mapping.init
          mapping.biopetScript
          addAll(mapping.functions) // Add functions of mapping to curent function pool
          Some(mapping.finalBamFile)
        } else if (config.contains("bam")) {
          var bamFile: File = config("bam")
          if (!bamFile.exists) throw new IllegalStateException("Bam in config does not exist, file: " + bamFile)

          if (config("bam_to_fastq", default = false).asBoolean) {
            val samToFastq = SamToFastq(qscript, bamFile, libDir + sampleId + "-" + libraryId + ".R1.fastq",
              libDir + sampleId + "-" + libraryId + ".R2.fastq")
            samToFastq.isIntermediate = true
            qscript.add(samToFastq)
            mapping.input_R1 = samToFastq.fastqR1
            mapping.input_R2 = samToFastq.fastqR2
            mapping.init
            mapping.biopetScript
            addAll(mapping.functions) // Add functions of mapping to curent function pool
            Some(mapping.finalBamFile)
          } else {
            var readGroupOke = true
            val inputSam = SamReaderFactory.makeDefault.open(bamFile)
            val header = inputSam.getFileHeader.getReadGroups
            for (readGroup <- inputSam.getFileHeader.getReadGroups) {
              if (readGroup.getSample != sampleId) logger.warn("Sample ID readgroup in bam file is not the same")
              if (readGroup.getLibrary != libraryId) logger.warn("Library ID readgroup in bam file is not the same")
              if (readGroup.getSample != sampleId || readGroup.getLibrary != libraryId) readGroupOke = false
            }
            inputSam.close

            if (!readGroupOke) {
              if (config("correct_readgroups", default = false)) {
                logger.info("Correcting readgroups, file:" + bamFile)
                val aorrg = AddOrReplaceReadGroups(qscript, bamFile, new File(libDir + sampleId + "-" + libraryId + ".bam"))
                aorrg.RGID = sampleId + "-" + libraryId
                aorrg.RGLB = libraryId
                aorrg.RGSM = sampleId
                aorrg.isIntermediate = true
                qscript.add(aorrg)
                bamFile = aorrg.output
              } else throw new IllegalStateException("Sample readgroup and/or library of input bamfile is not correct, file: " + bamFile +
                "\nPlease note that it is possible to set 'correct_readgroups' to true in the config to automatic fix this")
            }
            addAll(BamMetrics(qscript, bamFile, libDir + "metrics/").functions)

            Some(bamFile)
          }
        } else {
          logger.error("Sample: " + sampleId + ": No R1 found for run: " + libraryId)
          None
        }

        if (bamFile.isDefined) {
          gatkVariantcalling.inputBams = List(bamFile.get)
          gatkVariantcalling.variantcalling = config("library_variantcalling", default = false)
          gatkVariantcalling.preProcesBams = true
          gatkVariantcalling.init
          gatkVariantcalling.biopetScript
          addAll(gatkVariantcalling.functions)
        }
      }
    }

    /** sample variantcalling */
    val gatkVariantcalling = new GatkVariantcalling(qscript)
    gatkVariantcalling.sampleID = sampleId
    gatkVariantcalling.outputDir = sampleDir + "/variantcalling/"

    protected def addJobs(): Unit = {
      addPerLibJobs()
      gatkVariantcalling.inputBams = libraries.map(_._2.mapping.finalBamFile).toList
      gatkVariantcalling.preProcesBams = false
      if (!singleSampleCalling) {
        gatkVariantcalling.useHaplotypecaller = false
        gatkVariantcalling.useUnifiedGenotyper = false
      }
      gatkVariantcalling.init
      gatkVariantcalling.biopetScript
      addAll(gatkVariantcalling.functions)
    }
  }

  def init() {
    if (outputDir == null) throw new IllegalStateException("Missing Output directory on gatk module")
    else if (!outputDir.endsWith("/")) outputDir += "/"
  }

  val multisampleVariantcalling = new GatkVariantcalling(this) {
    override def configName = "gatkvariantcalling"
    override def configPath: List[String] = super.configPath ::: "multisample" :: Nil
  }

  def biopetScript(): Unit = {
    addSamplesJobs()
  }

  def addMultiSampleJobs(): Unit = {
    val gvcfFiles: List[File] = if (mergeGvcfs && externalGvcfs.size + samples.size > 1) {
      val newFile = outputDir + "merged.gvcf.vcf.gz"
      add(CombineGVCFs(this, externalGvcfs ++ samples.map(_._2.gatkVariantcalling.scriptOutput.gvcfFile), newFile))
      List(newFile)
    } else externalGvcfs ++ samples.map(_._2.gatkVariantcalling.scriptOutput.gvcfFile)

    if (!skipGenotyping && gvcfFiles.size > 0) {
      if (jointGenotyping) {
        val gatkGenotyping = new GatkGenotyping(this)
        gatkGenotyping.inputGvcfs = gvcfFiles
        gatkGenotyping.outputDir = outputDir + "genotyping/"
        gatkGenotyping.init
        gatkGenotyping.biopetScript
        addAll(gatkGenotyping.functions)
        var vcfFile = gatkGenotyping.outputFile
      }
    } else logger.warn("No gVCFs to genotype")

    if (jointVariantcalling) {
      val allBamfiles = samples.map(_._2.gatkVariantcalling.scriptOutput.bamFiles).toList.fold(Nil)(_ ++ _)
      val allRawVcfFiles = samples.map(_._2.gatkVariantcalling.scriptOutput.rawVcfFile).filter(_ != null).toList

      val gatkVariantcalling = new GatkVariantcalling(this) {
        override def configName = "gatkvariantcalling"
        override def configPath: List[String] = super.configPath ::: "multisample" :: Nil
      }

      if (gatkVariantcalling.useMpileup) {
        val cvRaw = CombineVariants(this, allRawVcfFiles.toList, outputDir + "variantcalling/multisample.raw.vcf.gz")
        add(cvRaw)
        gatkVariantcalling.rawVcfInput = cvRaw.out
      }

      multisampleVariantcalling.preProcesBams = false
      multisampleVariantcalling.doublePreProces = false
      multisampleVariantcalling.inputBams = allBamfiles.toList
      multisampleVariantcalling.outputDir = outputDir + "variantcalling"
      multisampleVariantcalling.outputName = "multisample"
      multisampleVariantcalling.init
      multisampleVariantcalling.biopetScript
      addAll(multisampleVariantcalling.functions)

      if (config("inputtype", default = "dna").asString != "rna" && config("recalibration", default = false).asBoolean) {
        val recalibration = new GatkVariantRecalibration(this)
        recalibration.inputVcf = multisampleVariantcalling.scriptOutput.finalVcfFile
        recalibration.bamFiles = allBamfiles
        recalibration.outputDir = outputDir + "recalibration/"
        recalibration.init
        recalibration.biopetScript
      }
    }
  }
}

object GatkPipeline extends PipelineCommand
