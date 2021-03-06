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

import java.io.File

import com.google.common.io.Files
import org.apache.commons.io.FileUtils
import org.broadinstitute.gatk.queue.QSettings
import org.scalatest.Matchers
import org.scalatest.testng.TestNGSuite
import org.testng.annotations.{ AfterClass, DataProvider, Test }

import nl.lumc.sasc.biopet.core.config.Config
import nl.lumc.sasc.biopet.extensions.{ Sickle, Gzip, Zcat }
import nl.lumc.sasc.biopet.tools.Seqstat
import nl.lumc.sasc.biopet.tools.FastqSync
import nl.lumc.sasc.biopet.utils.ConfigUtils

/**
 * Created by pjvan_thof on 2/11/15.
 */
class FlexiprepTest extends TestNGSuite with Matchers {

  def initPipeline(map: Map[String, Any]): Flexiprep = {
    new Flexiprep() {
      override def configName = "flexiprep"
      override def globalConfig = new Config(map)
      qSettings = new QSettings
      qSettings.runName = "test"
    }
  }

  @DataProvider(name = "flexiprepOptions")
  def flexiprepOptions = {
    val paired = Array(true, false)
    val skipTrims = Array(true, false)
    val skipClips = Array(true, false)
    val zipped = Array(true, false)

    for (
      pair <- paired;
      skipTrim <- skipTrims;
      skipClip <- skipClips;
      zip <- zipped
    ) yield Array("", pair, skipTrim, skipClip, zip)
  }

  @Test(dataProvider = "flexiprepOptions")
  def testFlexiprep(f: String, paired: Boolean, skipTrim: Boolean, skipClip: Boolean, zipped: Boolean) = {
    val map = ConfigUtils.mergeMaps(Map("output_dir" -> FlexiprepTest.outputDir,
      "skip_trim" -> skipTrim,
      "skip_clip" -> skipClip
    ), Map(FlexiprepTest.executables.toSeq: _*))
    val flexiprep: Flexiprep = initPipeline(map)

    flexiprep.input_R1 = new File(flexiprep.outputDir, "bla_R1.fq" + (if (zipped) ".gz" else ""))
    if (paired) flexiprep.input_R2 = Some(new File(flexiprep.outputDir, "bla_R2.fq" + (if (zipped) ".gz" else "")))
    flexiprep.sampleId = Some("1")
    flexiprep.libId = Some("1")
    flexiprep.script()

    flexiprep.functions.count(_.isInstanceOf[Fastqc]) shouldBe (
      if (paired && (skipClip && skipTrim)) 2
      else if (!paired && (skipClip && skipTrim)) 1
      else if (paired && !(skipClip && skipTrim)) 4
      else if (!paired && !(skipClip && skipTrim)) 2)
    flexiprep.functions.count(_.isInstanceOf[Seqstat]) shouldBe (if (paired) 4 else 2)
    flexiprep.functions.count(_.isInstanceOf[Zcat]) shouldBe (if (zipped) (if (paired) 2 else 1) else 0)
    flexiprep.functions.count(_.isInstanceOf[SeqtkSeq]) shouldBe (if (paired) 2 else 1)
    flexiprep.functions.count(_.isInstanceOf[Cutadapt]) shouldBe (if (skipClip) 0 else (if (paired) 2 else 1))
    flexiprep.functions.count(_.isInstanceOf[FastqSync]) shouldBe (if (skipClip) 0 else (if (paired) 1 else 0))
    flexiprep.functions.count(_.isInstanceOf[Sickle]) shouldBe (if (skipTrim) 0 else 1)
    flexiprep.functions.count(_.isInstanceOf[Gzip]) shouldBe (if (paired) 2 else 1)
  }

  // remove temporary run directory all tests in the class have been run
  @AfterClass def removeTempOutputDir() = {
    FileUtils.deleteDirectory(FlexiprepTest.outputDir)
  }
}

object FlexiprepTest {
  val outputDir = Files.createTempDir()

  val executables = Map(
    "seqstat" -> Map("exe" -> "test"),
    "fastqc" -> Map("exe" -> "test"),
    "seqtk" -> Map("exe" -> "test"),
    "sickle" -> Map("exe" -> "test"),
    "cutadapt" -> Map("exe" -> "test")
  )
}
