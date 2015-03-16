package nl.lumc.sasc.biopet.pipelines.gatk

import nl.lumc.sasc.biopet.core.PipelineCommand
import nl.lumc.sasc.biopet.core.config.Configurable
import nl.lumc.sasc.biopet.pipelines.basty.BastyTrait
import org.broadinstitute.gatk.queue.QScript

/**
 * Created by pjvan_thof on 3/4/15.
 */
class Basty(val root: Configurable) extends QScript with BastyTrait {
  qscript =>
  def this() = this(null)

  override def variantcallers = List("unifiedgenotyper")

  override lazy val shiva = new Shiva(qscript)
}

object Basty extends PipelineCommand