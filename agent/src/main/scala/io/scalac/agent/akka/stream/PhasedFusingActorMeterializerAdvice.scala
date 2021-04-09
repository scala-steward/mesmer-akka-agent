package io.scalac.agent.akka.stream

import akka.AkkaMirrorTypes
import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter._
import net.bytebuddy.asm.Advice._

import io.scalac.core.event.EventBus
import io.scalac.core.event.TagEvent
import io.scalac.core.model.Tag

class PhasedFusingActorMeterializerAdvice

object PhasedFusingActorMeterializerAdvice {

  @OnMethodExit
  def getPhases(@Return ref: ActorRef, @This self: AkkaMirrorTypes.ExtendedActorMaterializerMirror): Unit =
    EventBus(self.system.toTyped).publishEvent(TagEvent(ref, Tag.stream))
}
