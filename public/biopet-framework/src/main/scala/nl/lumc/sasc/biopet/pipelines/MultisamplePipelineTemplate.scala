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
package nl.lumc.sasc.biopet.pipelines

import nl.lumc.sasc.biopet.core.{ PipelineCommand, MultiSampleQScript, BiopetQScript }
import nl.lumc.sasc.biopet.core.config.Configurable
import org.broadinstitute.gatk.queue.QScript

class MultisamplePipelineTemplate(val root: Configurable) extends QScript with MultiSampleQScript {
  def this() = this(null)

  class Sample(val sampleId: String) extends AbstractSample {

    val libraries: Map[String, Library] = getLibrariesIds.map(id => id -> new Library(id)).toMap

    class Library(val libraryId: String) extends AbstractLibrary {
      def runJobs(): Unit = {
        // Library jobs
      }
    }

    def runJobs(): Unit = {
      // Sample jobs
    }
  }

  def init(): Unit = {
    samples = getSamplesIds.map(id => id -> new Sample(id)).toMap
  }

  def biopetScript() {
  }
}

object MultisamplePipelineTemplate extends PipelineCommand