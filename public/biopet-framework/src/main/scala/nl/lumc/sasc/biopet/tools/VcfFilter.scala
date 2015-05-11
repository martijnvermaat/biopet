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
package nl.lumc.sasc.biopet.tools

import htsjdk.variant.variantcontext.writer.AsyncVariantContextWriter
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder
import htsjdk.variant.vcf.{ VCFHeader, VCFFileReader }
import htsjdk.variant.variantcontext.VariantContext
import java.io.File
import java.util.ArrayList
import nl.lumc.sasc.biopet.core.BiopetJavaCommandLineFunction
import nl.lumc.sasc.biopet.core.ToolCommand
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.utils.commandline.{ Output, Input }
import scala.collection.JavaConversions._
import scala.io.Source

class VcfFilter(val root: Configurable) extends BiopetJavaCommandLineFunction {
  javaMainClass = getClass.getName

  @Input(doc = "Input vcf", shortName = "I", required = true)
  var inputVcf: File = _

  @Output(doc = "Output vcf", shortName = "o", required = false)
  var outputVcf: File = _

  var minSampleDepth: Option[Int] = config("min_sample_depth")
  var minTotalDepth: Option[Int] = config("min_total_depth")
  var minAlternateDepth: Option[Int] = config("min_alternate_depth")
  var minSamplesPass: Option[Int] = config("min_samples_pass")
  var filterRefCalls: Boolean = config("filter_ref_calls", default = false)

  override val defaultCoreMemory = 1.0

  override def commandLine = super.commandLine +
    required("-I", inputVcf) +
    required("-o", outputVcf) +
    optional("--minSampleDepth", minSampleDepth) +
    optional("--minTotalDepth", minTotalDepth) +
    optional("--minAlternateDepth", minAlternateDepth) +
    optional("--minSamplesPass", minSamplesPass) +
    conditional(filterRefCalls, "--filterRefCalls")
}

object VcfFilter extends ToolCommand {
  case class Trio(child: String, father: String, mother: String) {
    def this(arg: String) = {
      this(arg.split(":")(0), arg.split(":")(1), arg.split(":")(2))
    }
  }

  case class Args(inputVcf: File = null,
                  outputVcf: File = null,
                  invertedOutputVcf: Option[File] = None,
                  minQualScore: Option[Double] = None,
                  minSampleDepth: Int = -1,
                  minTotalDepth: Int = -1,
                  minAlternateDepth: Int = -1,
                  minSamplesPass: Int = 1,
                  minBamAlternateDepth: Int = 0,
                  mustHaveVariant: List[String] = Nil,
                  calledIn: List[String] = Nil,
                  deNovoInSample: String = null,
                  resToDom: List[Trio] = Nil,
                  trioCompound: List[Trio] = Nil,
                  deNovoTrio: List[Trio] = Nil,
                  trioLossOfHet: List[Trio] = Nil,
                  diffGenotype: List[(String, String)] = Nil,
                  filterHetVarToHomVar: List[(String, String)] = Nil,
                  filterRefCalls: Boolean = false,
                  filterNoCalls: Boolean = false,
                  iDset: Set[String] = Set()) extends AbstractArgs

  class OptParser extends AbstractOptParser {
    opt[File]('I', "inputVcf") required () maxOccurs (1) valueName ("<file>") action { (x, c) =>
      c.copy(inputVcf = x)
    } text ("Input vcf file")
    opt[File]('o', "outputVcf") required () maxOccurs (1) valueName ("<file>") action { (x, c) =>
      c.copy(outputVcf = x)
    } text ("Output vcf file")
    opt[File]("invertedOutputVcf") maxOccurs (1) valueName ("<file>") action { (x, c) =>
      c.copy(invertedOutputVcf = Some(x))
    } text ("inverted output vcf file")
    opt[Int]("minSampleDepth") unbounded () valueName ("<int>") action { (x, c) =>
      c.copy(minSampleDepth = x)
    } text ("Min value for DP in genotype fields")
    opt[Int]("minTotalDepth") unbounded () valueName ("<int>") action { (x, c) =>
      c.copy(minTotalDepth = x)
    } text ("Min value of DP field in INFO fields")
    opt[Int]("minAlternateDepth") unbounded () valueName ("<int>") action { (x, c) =>
      c.copy(minAlternateDepth = x)
    } text ("Min value of AD field in genotype fields")
    opt[Int]("minSamplesPass") unbounded () valueName ("<int>") action { (x, c) =>
      c.copy(minSamplesPass = x)
    } text ("Min number off samples to pass --minAlternateDepth, --minBamAlternateDepth and --minSampleDepth")
    opt[Int]("minBamAlternateDepth") unbounded () valueName ("<int>") action { (x, c) =>
      c.copy(minBamAlternateDepth = x)
    } // TODO: Convert this to more generic filter
    opt[String]("resToDom") unbounded () valueName ("<child:father:mother>") action { (x, c) =>
      c.copy(resToDom = new Trio(x) :: c.resToDom)
    } text ("Only shows variants where child is homozygous and both parants hetrozygous")
    opt[String]("trioCompound") unbounded () valueName ("<child:father:mother>") action { (x, c) =>
      c.copy(trioCompound = new Trio(x) :: c.trioCompound)
    } text ("Only shows variants where child is a compound variant combined from both parants")
    opt[String]("deNovoInSample") maxOccurs (1) unbounded () valueName ("<sample>") action { (x, c) =>
      c.copy(deNovoInSample = x)
    } text ("Only show variants that contain unique alleles in complete set for given sample")
    opt[String]("deNovoTrio") unbounded () valueName ("<child:father:mother>") action { (x, c) =>
      c.copy(deNovoTrio = new Trio(x) :: c.deNovoTrio)
    } text ("Only show variants that are denovo in the trio")
    opt[String]("trioLossOfHet") unbounded () valueName ("<child:father:mother>") action { (x, c) =>
      c.copy(trioLossOfHet = new Trio(x) :: c.trioLossOfHet)
    } text ("Only show variants where a loss of hetrozygosity is detected")
    opt[String]("mustHaveVariant") unbounded () valueName ("<sample>") action { (x, c) =>
      c.copy(mustHaveVariant = x :: c.mustHaveVariant)
    } text ("Given sample must have 1 alternative allele")
    opt[String]("calledIn") unbounded () valueName ("<sample>") action { (x, c) =>
      c.copy(calledIn = x :: c.calledIn)
    } text ("Must be called in this sample")
    opt[String]("diffGenotype") unbounded () valueName ("<sample:sample>") action { (x, c) =>
      c.copy(diffGenotype = (x.split(":")(0), x.split(":")(1)) :: c.diffGenotype)
    } validate { x => if (x.split(":").length == 2) success else failure("--notSameGenotype should be in this format: sample:sample")
    } text ("Given samples must have a different genotype")
    opt[String]("filterHetVarToHomVar") unbounded () valueName ("<sample:sample>") action { (x, c) =>
      c.copy(filterHetVarToHomVar = (x.split(":")(0), x.split(":")(1)) :: c.filterHetVarToHomVar)
    } validate { x => if (x.split(":").length == 2) success else failure("--filterHetVarToHomVar should be in this format: sample:sample")
    } text ("If variants in sample 1 are heterogeneous and alternative alleles are homogeneous in sample 2 variants are filtered")
    opt[Unit]("filterRefCalls") unbounded () action { (x, c) =>
      c.copy(filterRefCalls = true)
    } text ("Filter when there are only ref calls")
    opt[Unit]("filterNoCalls") unbounded () action { (x, c) =>
      c.copy(filterNoCalls = true)
    } text ("Filter when there are only no calls")
    opt[Double]("minQualScore") unbounded () action { (x, c) =>
      c.copy(minQualScore = Some(x))
    } text ("Min qual score")
    opt[String]("id") unbounded () action { (x, c) =>
      c.copy(iDset = c.iDset + x)
    } text ("Id that may pass the filter")
    opt[File]("idFile") unbounded () action { (x, c) =>
      c.copy(iDset = c.iDset ++ Source.fromFile(x).getLines())
    } text ("File that contain list of IDs to get from vcf file")
  }

  var commandArgs: Args = _

  /**
   * @param args the command line arguments
   */
  def main(args: Array[String]): Unit = {
    logger.info("Start")
    val argsParser = new OptParser
    commandArgs = argsParser.parse(args, Args()) getOrElse sys.exit(1)

    val reader = new VCFFileReader(commandArgs.inputVcf, false)
    val header = reader.getFileHeader
    val writer = new AsyncVariantContextWriter(new VariantContextWriterBuilder().
      setOutputFile(commandArgs.outputVcf).
      setReferenceDictionary(header.getSequenceDictionary).
      build)
    writer.writeHeader(header)

    val invertedWriter = commandArgs.invertedOutputVcf.collect {
      case x => new VariantContextWriterBuilder().
        setOutputFile(x).
        setReferenceDictionary(header.getSequenceDictionary).
        build
    }
    invertedWriter.foreach(_.writeHeader(header))

    var counterTotal = 0
    var counterLeft = 0
    for (record <- reader) {
      if (minQualscore(record) &&
        filterRefCalls(record) &&
        filterNoCalls(record) &&
        minTotalDepth(record) &&
        minSampleDepth(record) &&
        minAlternateDepth(record) &&
        minBamAlternateDepth(record, header) &&
        mustHaveVariant(record) &&
        called(record) &&
        notSameGenotype(record) &&
        filterHetVarToHomVar(record) &&
        denovoInSample(record) &&
        denovoTrio(record, commandArgs.deNovoTrio) &&
        denovoTrio(record, commandArgs.trioLossOfHet, true) &&
        resToDom(record, commandArgs.resToDom) &&
        trioCompound(record, commandArgs.trioCompound) &&
        inIdSet(record)) {
        writer.add(record)
        counterLeft += 1
      } else
        invertedWriter.foreach(_.add(record))
      counterTotal += 1
      if (counterTotal % 100000 == 0) logger.info(counterTotal + " variants processed, " + counterLeft + " left")
    }
    logger.info(counterTotal + " variants processed, " + counterLeft + " left")
    reader.close
    writer.close
    invertedWriter.foreach(_.close())
    logger.info("Done")
  }

  def called(record: VariantContext): Boolean = {
    if (!commandArgs.calledIn.forall(record.getGenotype(_).isCalled)) false
    else true
  }

  def minQualscore(record: VariantContext): Boolean = {
    if (commandArgs.minQualScore.isEmpty) return true
    record.getPhredScaledQual >= commandArgs.minQualScore.get
  }

  def filterRefCalls(record: VariantContext): Boolean = {
    if (commandArgs.filterNoCalls) record.getGenotypes.exists(g => !g.isHomRef)
    else true
  }

  def filterNoCalls(record: VariantContext): Boolean = {
    if (commandArgs.filterNoCalls) record.getGenotypes.exists(g => !g.isNoCall)
    else true
  }

  def minTotalDepth(record: VariantContext): Boolean = {
    record.getAttributeAsInt("DP", -1) >= commandArgs.minTotalDepth
  }

  def minSampleDepth(record: VariantContext): Boolean = {
    record.getGenotypes.count(genotype => {
      val DP = if (genotype.hasDP) genotype.getDP else -1
      DP >= commandArgs.minSampleDepth
    }) >= commandArgs.minSamplesPass
  }

  def minAlternateDepth(record: VariantContext): Boolean = {
    record.getGenotypes.count(genotype => {
      val AD = if (genotype.hasAD) List(genotype.getAD: _*) else Nil
      if (!AD.isEmpty) AD.tail.count(_ >= commandArgs.minAlternateDepth) > 0 else true
    }) >= commandArgs.minSamplesPass
  }

  def minBamAlternateDepth(record: VariantContext, header: VCFHeader): Boolean = {
    val bamADFields = (for (line <- header.getInfoHeaderLines if line.getID.startsWith("BAM-AD-")) yield line.getID).toList

    val bamADvalues = (for (field <- bamADFields) yield {
      record.getAttribute(field, new ArrayList) match {
        case t: ArrayList[_] if t.length > 1 => {
          for (i <- 1 until t.size) yield {
            t(i) match {
              case a: Int    => a > commandArgs.minBamAlternateDepth
              case a: String => a.toInt > commandArgs.minBamAlternateDepth
              case _         => false
            }
          }
        }
        case _ => List(false)
      }
    }).flatten

    return commandArgs.minBamAlternateDepth <= 0 || bamADvalues.count(_ == true) >= commandArgs.minSamplesPass
  }

  def mustHaveVariant(record: VariantContext): Boolean = {
    return !commandArgs.mustHaveVariant.map(record.getGenotype(_)).exists(a => a.isHomRef || a.isNoCall)
  }

  def notSameGenotype(record: VariantContext): Boolean = {
    for ((sample1, sample2) <- commandArgs.diffGenotype) {
      val genotype1 = record.getGenotype(sample1)
      val genotype2 = record.getGenotype(sample2)
      if (genotype1.sameGenotype(genotype2)) return false
    }
    return true
  }

  def filterHetVarToHomVar(record: VariantContext): Boolean = {
    for ((sample1, sample2) <- commandArgs.filterHetVarToHomVar) {
      val genotype1 = record.getGenotype(sample1)
      val genotype2 = record.getGenotype(sample2)
      if (genotype1.isHet && !genotype1.getAlleles.forall(_.isNonReference)) {
        for (allele <- genotype1.getAlleles if allele.isNonReference) {
          if (genotype2.getAlleles.forall(_.basesMatch(allele))) return false
        }
      }
    }
    return true
  }

  def denovoInSample(record: VariantContext): Boolean = {
    if (commandArgs.deNovoInSample == null) return true
    val genotype = record.getGenotype(commandArgs.deNovoInSample)
    for (allele <- genotype.getAlleles if allele.isNonReference) {
      for (g <- record.getGenotypes if g.getSampleName != commandArgs.deNovoInSample) {
        if (g.getAlleles.exists(_.basesMatch(allele))) return false
      }
    }
    return true
  }

  def resToDom(record: VariantContext, trios: List[Trio]): Boolean = {
    for (trio <- trios) {
      val child = record.getGenotype(trio.child)

      if (child.isHomVar && child.getAlleles.forall(allele => {
        record.getGenotype(trio.father).countAllele(allele) == 1 &&
          record.getGenotype(trio.mother).countAllele(allele) == 1
      })) return true
    }
    return trios.isEmpty
  }

  def trioCompound(record: VariantContext, trios: List[Trio]): Boolean = {
    for (trio <- trios) {
      val child = record.getGenotype(trio.child)

      if (child.isHetNonRef && child.getAlleles.forall(allele => {
        record.getGenotype(trio.father).countAllele(allele) >= 1 &&
          record.getGenotype(trio.mother).countAllele(allele) >= 1
      })) return true
    }
    return trios.isEmpty
  }

  def denovoTrio(record: VariantContext, trios: List[Trio], onlyLossHet: Boolean = false): Boolean = {
    for (trio <- trios) {
      val child = record.getGenotype(trio.child)
      val father = record.getGenotype(trio.father)
      val mother = record.getGenotype(trio.mother)

      for (allele <- child.getAlleles) {
        val childCount = child.countAllele(allele)
        val fatherCount = father.countAllele(allele)
        val motherCount = mother.countAllele(allele)

        if (onlyLossHet) {
          if (childCount == 2 && (
            (fatherCount == 2 && motherCount == 0) ||
            (fatherCount == 0 && motherCount == 2))) return true
        } else {
          if (childCount == 1 && fatherCount == 0 && motherCount == 0) return true
          else if (childCount == 2 && (fatherCount == 0 || motherCount == 0)) return true
        }
      }
    }
    return trios.isEmpty
  }

  def inIdSet(record: VariantContext): Boolean = {
    if (commandArgs.iDset.isEmpty) true
    else record.getID.split(",").exists(commandArgs.iDset.contains(_))
  }
}