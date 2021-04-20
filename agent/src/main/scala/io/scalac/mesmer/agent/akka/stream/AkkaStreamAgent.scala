package io.scalac.mesmer.agent.akka.stream

import akka.ActorGraphInterpreterAdvice
import akka.actor.Props
import akka.stream.GraphStageIslandAdvice
import akka.stream.impl.fusing.ActorGraphInterpreterProcessEventAdvice
import akka.stream.impl.fusing.ActorGraphInterpreterTryInitAdvice

import io.scalac.mesmer.agent.Agent
import io.scalac.mesmer.agent.util.i13n._
import io.scalac.mesmer.core.model.Module
import io.scalac.mesmer.core.model.SupportedModules
import io.scalac.mesmer.core.model.SupportedVersion

object AkkaStreamAgent extends InstrumentModuleFactory {

  private[akka] val moduleName = Module("akka-stream")

  protected val supportedModules: SupportedModules = SupportedModules(moduleName, SupportedVersion.any)

  /**
   * actorOf methods is called when island decide to materialize itself
   */
  private val phasedFusingActorMeterializerAgent =
    instrument("akka.stream.impl.PhasedFusingActorMaterializer")
      .intercept[PhasedFusingActorMeterializerAdvice](method("actorOf").takesArguments[Props, String])

  private val graphInterpreterAgent =
    instrument("akka.stream.impl.fusing.GraphInterpreter")
      .intercept[GraphInterpreterPushAdvice]("processPush")
      .intercept[GraphInterpreterPullAdvice]("processPull")

  private val graphInterpreterConnectionAgent =
    instrument("akka.stream.impl.fusing.GraphInterpreter$Connection")
      .defineField[Long](ConnectionOps.PullCounterVarName)
      .defineField[Long](ConnectionOps.PushCounterVarName)

  private val actorGraphInterpreterAgent =
    instrument("akka.stream.impl.fusing.ActorGraphInterpreter")
      .visit[ActorGraphInterpreterAdvice]("receive")
      .visit[ActorGraphInterpreterProcessEventAdvice]("processEvent")
      .visit[ActorGraphInterpreterTryInitAdvice]("tryInit")

  private val graphStageIslandAgent =
    instrument("akka.stream.impl.GraphStageIsland")
      .visit[GraphStageIslandAdvice]("materializeAtomic")

  val agent: Agent = Agent(
    phasedFusingActorMeterializerAgent,
    actorGraphInterpreterAgent,
    graphInterpreterAgent,
    graphInterpreterConnectionAgent,
    graphStageIslandAgent
  )
}