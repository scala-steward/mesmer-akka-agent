package io.scalac.extension.persistence

import java.util.concurrent.ConcurrentHashMap

import io.scalac.extension.config.CleaningConfig
import io.scalac.extension.event.PersistenceEvent.RecoveryStarted
import io.scalac.extension.util.TestOps
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Random

class CleanableRecoveryStorageTest extends AnyFlatSpec with Matchers with TestOps {

  "CleanableRecoveryStorage" should "clean internal buffer" in {
    val buffer = new ConcurrentHashMap[String, RecoveryStarted]()
    val maxStaleness = 10_000L
    val config = CleaningConfig(maxStaleness, 10.seconds)
    val baseTimestamp = 100_000L

    val staleEvents = List.fill(10) {
      val staleness = Random.nextLong(80_000) + maxStaleness
      val id = createUniqueId
      RecoveryStarted(s"/some/path/${id}", id, baseTimestamp - staleness)
    }
    val freshEvents = List.fill(10) {
      val id = createUniqueId
      val staleness = Random.nextLong(maxStaleness)
      RecoveryStarted(s"/some/path/${id}", id, baseTimestamp - staleness)
    }

    val sut    = new CleanableRecoveryStorage(buffer.asScala)(config) {
      override protected def timestamp: Long = baseTimestamp
    }

    for {
      event <- staleEvents ++ freshEvents
    } sut.recoveryStarted(event)

    sut.clean()

    buffer should have size(freshEvents.size)
    buffer.values should contain theSameElementsAs(freshEvents)
  }
}
