/**
 * Copyright (c) 2014 Leiden University Medical Center - Sequencing Analysis Support Core <sasc@lumc.nl>
 * @author Wibowo Arindrarto <w.arindrarto@lumc.nl>
 */
package nl.lumc.sasc.biopet.core.apps

import java.io.{ File, IOException }
import scala.collection.JavaConverters._
import scala.io.Source

import com.twitter.algebird.{ BF, BloomFilter }
import htsjdk.samtools.SAMFileReader
import htsjdk.samtools.SAMFileReader.QueryInterval
import htsjdk.samtools.SAMRecord
import org.apache.commons.io.FilenameUtils.getExtension
import org.broadinstitute.gatk.utils.commandline.{ Input, Output }

import nl.lumc.sasc.biopet.core.BiopetJavaCommandLineFunction
import nl.lumc.sasc.biopet.core.config.Configurable


class WipeReads(val root: Configurable) extends BiopetJavaCommandLineFunction {

  javaMainClass = getClass.getName

  @Input(doc = "Input BAM file (must be indexed)", shortName = "I", required = true)
  var inputBAM: File = _

  @Output(doc = "Output BAM", shortName = "o", required = true)
  var outputBAM: File = _

}


object WipeReads {

  type OptionMap = Map[String, Any]

  object Strand extends Enumeration {
    type Strand = Value
    val Identical, Opposite, Both = Value
  }

  // TODO: check that interval chrom is in the BAM file (optionally, when prepended with 'chr' too)
  def makeQueryIntervalFromFile(inFile: File, inBAM: SAMFileReader): Iterator[QueryInterval] = {

    case class RawInterval(chrom: String, start: Int, end: Int, strand: String)

    def makeRawIntervalFromBED(inFile: File): Iterator[RawInterval] =
    // BED file coordinates are 0-based, half open so we need to do some conversion
      Source.fromFile(inFile)
        .getLines()
        .filterNot(_.trim.isEmpty)
        .dropWhile(_.matches("^track | ^browser "))
        .map(line => line.trim.split("\t") match {
        case Array(chrom, start, end)                   => new RawInterval(chrom, start.toInt + 1, end.toInt, "")
        case Array(chrom, start, end, _, _, strand, _*) => new RawInterval(chrom, start.toInt + 1, end.toInt, strand)
      })

    def makeRawIntervalFromRefFlat(inFile: File): Iterator[RawInterval] = ???
    // convert coordinate to 1-based fully closed
    // parse chrom, start blocks, end blocks, strands

    def makeRawIntervalFromGTF(inFile: File): Iterator[RawInterval] = ???
    // convert coordinate to 1-based fully closed
    // parse chrom, start blocks, end blocks, strands

    // detect interval file format from extension
    val iterFunc: (File => Iterator[RawInterval]) =
      if (getExtension(inFile.toString.toLowerCase) == "bed")
        makeRawIntervalFromBED
      else
        throw new IllegalArgumentException("Unexpected interval file type: " + inFile.getPath)

    iterFunc(inFile)
      .filter(x => inBAM.getFileHeader.getSequenceIndex(x.chrom) > -1)
      .map(x => inBAM.makeQueryInterval(x.chrom, x.start, x.end))
  }

  // TODO: set minimum fraction for overlap
  def makeBloomFilter(iv: Iterator[QueryInterval],
                      inBAM: File,
                      inBAMIndex: File = null,
                      filterOutMulti: Boolean = true,
                      minMapQ: Int = 0,
                      readGroupIDs: Set[String] = Set()): BF = {

    // TODO: implement optional index creation
    def prepIndexedInputBAM(): SAMFileReader =
      if (inBAMIndex != null)
        new SAMFileReader(inBAM, inBAMIndex)
      else {
        val sfr = new SAMFileReader(inBAM)
        if (!sfr.hasIndex)
          throw new IllegalStateException("Input BAM file must be indexed")
        else
          sfr
      }

    // TODO: can we accumulate errors / exceptions instead of ignoring them?
    def monadicMateQuery(inBAM: SAMFileReader, rec: SAMRecord): Option[SAMRecord] =
      try {
        Some(inBAM.queryMate(rec))
      } catch {
        case e: Exception => None
      }

    val bloomSize: Int = 100000000
    val bloomFp: Double = 1e-10
    val firstBAM = prepIndexedInputBAM()
    val secondBAM = prepIndexedInputBAM()
    val bfm = BloomFilter(bloomSize, bloomFp, 13)

    // TODO: how to chain initTargets to filteredTargets directly?
    val initTargets: Iterator[Vector[SAMRecord]] = for {
      x <- firstBAM.queryOverlapping(iv.toArray).asScala
      // TODO: can we do without Vector here? It's only so we can flatten it later
    } yield Vector(Some(x), monadicMateQuery(secondBAM, x)).flatten

    val filteredTargets: Iterator[SAMRecord] = initTargets.flatten
      // filter on minimum MAPQ value
      .filter(x => x.getMappingQuality >= minMapQ)
      // filter on specific read group IDs
      .filter(x => if (readGroupIDs.size == 0) true else readGroupIDs.contains(x.getReadGroup.getReadGroupId))

    // if filtering out multi-mapped reads, our elements is the read name
    // otherwise it's the entire SAM string
    filteredTargets
      .map(x => if (filterOutMulti) x.getReadName else x.getSAMString)
      .foldLeft(bfm.create())(_.+(_))
  }

  def parseOption(opts: OptionMap, list: List[String]): OptionMap =
    list match {
      case Nil
          => opts
      case ("--inputBAM" | "-I") :: value :: tail if !opts.contains("inputBAM")
          => parseOption(opts ++ Map("inputBAM" -> checkInputBAM(new File(value))), tail)
      case ("--targetRegions" | "-l") :: value :: tail if !opts.contains("targetRegions")
          => parseOption(opts ++ Map("targetRegions" -> checkInputFile(new File(value))), tail)
      case ("--outputBAM" | "-o") :: value :: tail if !opts.contains("outputBAM")
          => parseOption(opts ++ Map("outputBAM" -> new File(value)), tail)
      // TODO: implementation
      case ("--minOverlapFraction" | "-f") :: value :: tail if !opts.contains("minOverlapFraction")
          => parseOption(opts ++ Map("minOverlapFraction" -> value.toDouble), tail)
      case ("--minMapQ" | "-Q") :: value :: tail if !opts.contains("minMapQ")
          => parseOption(opts ++ Map("minMapQ" -> value.toInt), tail)
      // TODO: implementation
      case ("--strand" | "-s") :: (value @ ("identical" | "opposite" | "both")) :: tail if !opts.contains("strand")
          => parseOption(opts ++ Map("strand" -> Strand.withName(value.capitalize)), tail)
      // TODO: implementation
      case ("--makeIndex") :: tail
          => parseOption(opts ++ Map("makeIndex" -> true), tail)
      case ("--limitToRegion" | "-limit") :: tail
          => parseOption(opts ++ Map("limitToRegion" -> true), tail)
      // TODO: better way to parse multiple flag values?
      case ("--readGroup" | "-RG") :: value :: tail if !opts.contains("readGroup")
          => parseOption(opts ++ Map("readGroup" -> value.split(",").toVector), tail)
      case option :: tail
          => throw new IllegalArgumentException("Unexpected or duplicate option flag: " + option)
    }

  private def validateOption(opts: OptionMap): Unit = {
    // TODO: better way to check for required arguments ~ use scalaz.Validation?
    if (opts.get("inputBAM") == None)
      throw new IllegalArgumentException("Input BAM not supplied")
    if (opts.get("targetRegions") == None)
      throw new IllegalArgumentException("Target regions file not supplied")
  }

  def checkInputFile(inFile: File): File =
    if (inFile.exists)
      inFile
    else
      throw new IOException("Input file " + inFile.getPath + " not found")

  def checkInputBAM(inBAM: File): File = {
    // input BAM must have a .bam.bai index
    if (new File(inBAM.getPath + ".bai").exists)
      checkInputFile(inBAM)
    else
      throw new IOException("Index for input BAM file " + inBAM.getPath + " not found")
  }

  def main(args: Array[String]): Unit = {

    if (args.length == 0) {
      println(usage)
      System.exit(1)
    }
    val options = parseOption(Map(), args.toList)
    validateOption(options)
  }

  val usage: String =
    """
      |usage: java -cp BiopetFramework.jar nl.lumc.sasc.biopet-core.apps.WipeReads [options] -I input -l regions -o output
      |
      |WipeReads - Tool for reads removal from an indexed BAM file.
      |
      |positional arguments:
      |  -I,--inputBAM              Input BAM file, must be indexed with '.bam.bai' extension
      |  -l,--targetRegions         Input BED file
      |  -o,--outputBAM             Output BAM file
      |
      |optional arguments:
      |  -RG,--readGroup            Read groups to remove (default: all)
      |  -Q,--minMapQ               Minimum MAPQ value of reads in target region (default: 0)
      |  --limitToRegion            Whether to remove only reads in the target regions and and
      |                             keep the same reads if they map to other regions
      |                             (default: not set)
      |
      |This tool will remove BAM records that overlaps a set of given regions.
      |By default, if the removed reads are also mapped to other regions outside
      |the given ones, they will also be removed.
    """.stripMargin


}
