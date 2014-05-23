package mesosphere.marathon.upgrade

import akka.actor._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.SchedulerDriver
import scala.concurrent.{Future, Promise}
import mesosphere.marathon.tasks.{TaskTracker, TaskQueue}
import akka.event.EventStream
import mesosphere.marathon.ConcurrentTaskUpgradeException
import scala.collection.mutable

class AppUpgradeManager(
  taskTracker: TaskTracker,
  taskQueue: TaskQueue,
  eventBus: EventStream
) extends Actor with ActorLogging {
  import AppUpgradeManager._
  import context.dispatcher

  type AppID = String

  var runningUpgrades: mutable.Map[AppID, ActorRef] = mutable.Map.empty

  def receive = {
    case Upgrade(driver, app, keepAlive) if !runningUpgrades.contains(app.id) =>
      val ref = context.actorOf(
        Props(
          classOf[AppUpgradeActor],
          self,
          driver,
          taskTracker,
          taskQueue,
          eventBus,
          app,
          keepAlive,
          sender))
      runningUpgrades += app.id -> ref

    case _: Upgrade =>
      sender ! Status.Failure(new ConcurrentTaskUpgradeException("Upgrade is already in progress"))

    case CancelUpgrade(appId) =>
      val origSender = sender
      runningUpgrades.remove(appId) match {
        case Some(ref) =>
          stopActor(ref) onComplete {
            case _ => origSender ! UpgradeCancelled(appId)
          }

        case _ => origSender ! UpgradeCancelled(appId)
      }

    case UpgradeFinished(id) =>
      log.info(s"Removing $id from list of running upgrades")
      runningUpgrades -= id
  }

  def stopActor(ref: ActorRef): Future[Boolean] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[StopActor], ref, promise))
    promise.future
  }
}

object AppUpgradeManager {
  case class Upgrade(driver: SchedulerDriver, app: AppDefinition, keepAlive: Int)
  case class CancelUpgrade(appId: String)

  case class UpgradeFinished(appId: String)
  case class UpgradeCancelled(appId: String)
}
