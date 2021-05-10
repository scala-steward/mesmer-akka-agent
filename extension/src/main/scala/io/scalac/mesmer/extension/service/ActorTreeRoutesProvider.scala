package io.scalac.mesmer.extension.service
import akka.actor.ExtendedActorSystem
import akka.actor.setup.Setup
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.management.scaladsl.{ ManagementRouteProvider, ManagementRouteProviderSettings }
import akka.util.Timeout
import akka.{ actor => classic }
import io.scalac.mesmer.core.model.Tag
import io.scalac.mesmer.extension.service.ActorInfoService.{ actorInfo, ActorInfo }
import io.scalac.mesmer.extension.service.ActorTreeService.Command.GetActors
import io.scalac.mesmer.extension.util.GenericBehaviors
import org.slf4j.LoggerFactory
import zio.Chunk
import zio.json._
import zio.json.ast.Json

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ Future, Promise }
import scala.util._
import scala.util.control.NoStackTrace

final case class ActorTreeRoutesProviderConfig(timeout: FiniteDuration)

final class ActorInfoServiceSetup(val actorInfoService: ActorInfoService) extends Setup

private[scalac] trait ActorInfoService {
  import ActorInfoService._

  def actorTree: Future[Option[NonEmptyTree[ActorInfo]]]
}

object ActorInfoService {

  final case class ActorInfo(actorName: String) extends AnyVal
  case object ServiceDiscoveryTimeout           extends Throwable with NoStackTrace

  private[service] def actorInfo(
    serviceDiscoveryTimeout: FiniteDuration
  )(implicit system: ActorSystem[_], askTimeout: Timeout): Future[ActorInfoService] = {

    val promise = Promise[ActorInfoService]()

    system.systemActorOf(
      GenericBehaviors.waitForServiceWithTimeout(actorTreeServiceKey, serviceDiscoveryTimeout)(
        ref =>
          Behaviors.setup[ActorTreeService.Command] { _ =>
            promise.success(fromTreeService(ref))
            Behaviors.stopped
          },
        Behaviors.setup[ActorTreeService.Command] { _ =>
          promise.failure(ServiceDiscoveryTimeout)
          Behaviors.stopped
        }
      ),
      "mesmer-actor-tree-routes-provider-service-discovery"
    )

    promise.future

  }

  def fromTreeService(
    ref: ActorRef[ActorTreeService.Command]
  )(implicit ec: ActorSystem[_], askTimeout: Timeout): ActorInfoService = new ActorInfoService {
    def actorTree: Future[Option[NonEmptyTree[ActorInfo]]] =
      ref
        .ask((ref: ActorRef[Seq[classic.ActorRef]]) => GetActors(Tag.all, ref))
        .map(extractInfoTree)(ec.executionContext)

    def extractInfoTree(actors: Seq[classic.ActorRef]): Option[NonEmptyTree[ActorInfo]] =
      NonEmptyTree.fromSeq(actors.map(ref => ActorInfo(ref.path.toStringWithoutAddress)))

    implicit val actorInfoPartialOrdering: PartialOrdering[ActorInfo] = new PartialOrdering[ActorInfo] {

      def tryCompare(x: ActorInfo, y: ActorInfo): Option[Int] =
        (x.actorName, y.actorName) match {
          case (xPath, yPath) if xPath == yPath          => Some(0)
          case (xPath, yPath) if xPath.startsWith(yPath) => Some(-1)
          case (xPath, yPath) if yPath.startsWith(xPath) => Some(1)
          case _                                         => None
        }

      def lteq(x: ActorInfo, y: ActorInfo): Boolean = actorLevel(x) <= actorLevel(y)
    }

    private def actorLevel(info: ActorInfo): Int = info.actorName.count(_ == '/')

  }
}

final class ActorTreeRoutesProvider(classicSystem: ExtendedActorSystem) extends ManagementRouteProvider {

  implicit val actorTreeEncoder: JsonEncoder[NonEmptyTree[ActorInfo]] = JsonEncoder[Json].contramap {
    _.foldRight[Json.Obj](Json.Obj()) { case (current, children) =>
      val childrenFields = children.foldLeft[Chunk[(String, Json)]](Chunk.empty)(_ ++ _.fields)
      Json.Obj(current.actorName -> Json.Obj(childrenFields))
    }
  }

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val settings: ActorTreeRoutesProviderConfig = ActorTreeRoutesProviderConfig(10.seconds)
  private implicit val timeout: Timeout               = settings.timeout
  private implicit val system                         = classicSystem.toTyped
  private lazy val actorInfoService: Future[ActorInfoService] =
    system.settings.setup
      .get[ActorInfoServiceSetup]
      .map(setup => Future.successful(setup.actorInfoService))
      .getOrElse {
        logger.debug("Checking if actorTree service is available")
        actorInfo(settings.timeout)
      }

  import ActorInfoService._
  import system.executionContext

  def routes(settings: ManagementRouteProviderSettings): Route = get {

    onComplete(actorInfoService.flatMap(_.actorTree)) {
      case Success(value) => complete(StatusCodes.OK, value.toJson)
      case Failure(ServiceDiscoveryTimeout) =>
        logger.error("Actor service unavailable")
        complete(StatusCodes.ServiceUnavailable)
      case Failure(ex) =>
        logger.error("Unexpected error occurred", ex)
        complete(StatusCodes.InternalServerError)
    }
  }
}
