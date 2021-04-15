package io.scalac.core.util

import com.typesafe.config._

object TestConfig {
  lazy val localActorProvider: Config =
    ConfigFactory.parseString("akka.actor.provider=local").withFallback(ConfigFactory.load("application-test"))
}