package dfp

import com.google.api.ads.dfp.axis.utils.v201411.StatementBuilder
import com.google.api.ads.dfp.axis.v201411.InventoryStatus
import common.{AkkaAgent, ExecutionContexts, Logging}
import conf.Configuration
import org.joda.time.DateTime

import scala.concurrent.Future

object AdUnitAgent extends ExecutionContexts with Logging {

  private val initialCache = AdUnitCache(DateTime.now, Map.empty)
  private lazy val cache = AkkaAgent[AdUnitCache](initialCache)

  def refresh(): Future[AdUnitCache] = {
    log.info("Loading ad units")
    cache alterOff { oldCache =>
      val freshAdUnits = loadActiveCoreSiteAdUnits()
      if (freshAdUnits.nonEmpty) {
        log.info("Ad units loaded")
        AdUnitCache(DateTime.now, freshAdUnits)
      } else {
        log.error("No ad units loaded so keeping old set")
        oldCache
      }
    }
  }

  private def loadActiveCoreSiteAdUnits(): Map[String, GuAdUnit] = {

    DfpServiceRegistry().fold(Map[String, GuAdUnit]()) { serviceRegistry =>

      val statementBuilder = new StatementBuilder()
        .where("status = :status")
        .withBindVariableValue("status", InventoryStatus._ACTIVE)

      val dfpAdUnits = DfpApiWrapper.fetchAdUnits(serviceRegistry, statementBuilder)

      val rootName = Configuration.commercial.dfpAdUnitRoot
      val rootAndDescendantAdUnits = dfpAdUnits filter { adUnit =>
        Option(adUnit.getParentPath) exists { path =>
          (path.length == 1 && adUnit.getName == rootName) || (path.length > 1 && path(1).getName
            == rootName)
        }
      }

      rootAndDescendantAdUnits.map { adUnit =>
        val path = adUnit.getParentPath.tail.map(_.getName).toSeq :+ adUnit.getName
        (adUnit.getId, GuAdUnit(adUnit.getId, path))
      }.toMap
    }
  }

  def get: AdUnitCache = cache.get()
}

case class AdUnitCache(timestamp: DateTime, adUnits: Map[String, GuAdUnit])