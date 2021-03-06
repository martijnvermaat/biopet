/**
 * Due to the license issue with GATK, this part of Biopet can only be used inside the
 * LUMC. Please refer to https://git.lumc.nl/biopet/biopet/wikis/home for instructions
 * on how to use this protected part of biopet or contact us at sasc@lumc.nl
 */
package nl.lumc.sasc.biopet.extensions.gatk.broad

import nl.lumc.sasc.biopet.core.config.Configurable

class UnifiedGenotyper(val root: Configurable) extends org.broadinstitute.gatk.queue.extensions.gatk.UnifiedGenotyper with GatkGeneral {
  if (config.contains("scattercount")) scatterCount = config("scattercount")
  if (config.contains("dbsnp")) this.dbsnp = config("dbsnp")
  sample_ploidy = config("ploidy")
  if (config.contains("allSitePLs")) this.allSitePLs = config("allSitePLs")

  stand_call_conf = config("stand_call_conf", default = 5)
  stand_emit_conf = config("stand_emit_conf", default = 0)

  if (config.contains("output_mode")) {
    import org.broadinstitute.gatk.tools.walkers.genotyper.OutputMode._
    config("output_mode").asString match {
      case "EMIT_ALL_CONFIDENT_SITES" => output_mode = EMIT_ALL_CONFIDENT_SITES
      case "EMIT_ALL_SITES"           => output_mode = EMIT_ALL_SITES
      case "EMIT_VARIANTS_ONLY"       => output_mode = EMIT_VARIANTS_ONLY
      case e                          => logger.warn("output mode '" + e + "' does not exist")
    }
  }

  override def beforeGraph {
    super.beforeGraph

    genotype_likelihoods_model = org.broadinstitute.gatk.tools.walkers.genotyper.GenotypeLikelihoodsCalculationModel.Model.BOTH
    nct = Some(getThreads(1))
    memoryLimit = Option(nct.getOrElse(1) * memoryLimit.getOrElse(2.0))
  }
}
