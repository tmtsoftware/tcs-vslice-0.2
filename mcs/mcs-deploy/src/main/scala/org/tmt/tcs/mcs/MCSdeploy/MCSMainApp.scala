package org.tmt.tcs.mcs.MCSdeploy

import java.net.InetAddress

import akka.actor.{typed, ActorRefFactory, ActorSystem, Scheduler}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import akka.actor.typed.scaladsl.adapter._
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.event.api.scaladsl.EventService
import csw.event.client.EventServiceFactory
import csw.location.api.models.{AkkaLocation, ComponentId}
import csw.location.api.models.ComponentType.Assembly
import csw.location.api.models.Connection.AkkaConnection
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.scaladsl.LoggingSystemFactory

import csw.params.commands.{CommandName, CommandResponse, Setup}
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.{Id, Prefix}
import csw.params.events.{Event, SystemEvent}
import org.tmt.tcs.mcs.MCSdeploy.constants.{DeployConstants, EventConstants}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
/*
This object acts as a client object to test execution of commands

 */
object MCSMainApp extends App {
  private val system: ActorSystem     = ActorSystemFactory.remote("Client-App")
  implicit val mat: ActorMaterializer = ActorMaterializer()
  // private val system: ActorSystem = ClusterAwareSettings.system
  private val locationService = HttpLocationServiceFactory.makeLocalClient(system, mat)
  private val host            = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("MCSMainApp", "0.1", host, system)

  import system._

  implicit val timeout: Timeout                 = Timeout(300.seconds)
  implicit val scheduler: Scheduler             = system.scheduler
  implicit def actorRefFactory: ActorRefFactory = system

  // implicit val ec: ExecutionContextExecutor     = system.dispatcher
  private val connection = AkkaConnection(ComponentId("McsAssembly", Assembly))

  private val axesKey: Key[String] = KeyType.StringKey.make("axes")
  private val azKey: Key[Double]   = KeyType.DoubleKey.make("AZ")
  private val elKey: Key[Double]   = KeyType.DoubleKey.make("EL")

  /**
   * Gets a reference to the running assembly from the location service, if found.
   */
  private def getAssembly: CommandService = {
    implicit val sys: typed.ActorSystem[Nothing] = system.toTyped
    //locationService.resolve(connection, 3000.seconds).map(_.map(new CommandService(_)))
    val akkaLocations: List[AkkaLocation] = Await.result(locationService.listByPrefix("tcs.mcs.assembly"), 10.seconds)
    CommandServiceFactory.make(akkaLocations.head)(sys)
  }
  private def getEventService: EventService = {
    locationService.resolve(connection, 3000.seconds)
    val eventService: EventService = new EventServiceFactory().make(locationService)(system)
    eventService
  }

  val prefix = Prefix("tmt.tcs.McsAssembly-Client")

//  var count: Integer = 0
  var simulationMode = "SimpleSimulator"

  var simulCmdSentTime: Long = System.currentTimeMillis()
  val resp0                  = Await.result(sendSimulationModeCommand(simulationMode), 10.seconds)
  println(s"SimulationMode command response is : $resp0 total time taken is : ${System.currentTimeMillis() - simulCmdSentTime}")

  var startupSentTime: Long = System.currentTimeMillis()
  val resp1                 = Await.result(sendStartupCommand(), 30.seconds)
  println(s"Startup command response is : $resp1 total time taken is : ${System.currentTimeMillis() - startupSentTime}")

  var datumCommandSentTime: Long = System.currentTimeMillis()
  val resp2                      = Await.result(sendDatumCommand(), 200.seconds)
  println(s"Datum command response is : $resp2 total time taken is : ${System.currentTimeMillis() - datumCommandSentTime}")

  var followCmdSentTime: Long = System.currentTimeMillis()
  val resp3                   = Await.result(sendFollowCommand(), 200.seconds)
  println(s"Follow command response is : $resp3 total time taken is : ${System.currentTimeMillis() - followCmdSentTime}")

  // val resp4 = Await.result(sendMoveCommand, 250.seconds)
  //println(s"Move command response is : $resp4 at : ${System.currentTimeMillis()}")

  var dummyImmCmd: Long = System.currentTimeMillis()
  val resp5             = Await.result(sendDummyImmediateCommand(), 200.seconds)
  println(s"Dummy immediate command Response is : $resp5 total time taken is : ${System.currentTimeMillis() - dummyImmCmd}")

  var dummyLongCmd: Long = System.currentTimeMillis()
  val resp6              = Await.result(sendDummyLongCommand(), 200.seconds)
  println(s"Dummy Long Command Response is : $resp6 total time taken is : ${System.currentTimeMillis() - dummyLongCmd}")

  /*var shutdownCmd: Long = System.currentTimeMillis()
  val resp7             = Await.result(sendShutDownCmd, 30.seconds)
  println(s"Shutdown Command Response is : ${resp7} total time taken is : ${System.currentTimeMillis() - shutdownCmd}")*/

  println(
    s"===========================================Command set completed ============================================================================="
  )

  /*new Thread(new Runnable { override def run(): Unit = startSubscribingEvents }).start()*/

  startSubscribingEvents()

  def startSubscribingEvents(): Unit = {
    println(" ** Started subscribing Events from Assembly ** ")
    val eventService = getEventService
    val subscriber   = eventService.defaultSubscriber
    subscriber.subscribeCallback(DeployConstants.currentPositionSet, event => processCurrentPosition(event))
    subscriber.subscribeCallback(DeployConstants.healthSet, event => processHealth(event))
    subscriber.subscribeCallback(DeployConstants.dummyEventKey, event => proecessDummyEvent(event))
  }
  def proecessDummyEvent(event: Event): Future[_] = {
    //println(s"** Received event : ${event} from Assembly. ** ")
    Future.successful[String]("Successfully processed Dummy event from assembly")
  }
  def processHealth(event: Event): Future[_] = {
    // println(s"*** Received health event: ${event} from assembly at time : ${Calendar.getInstance().getTime} *** ")
    val clientAppRecTime = System.currentTimeMillis()
    event match {
      case systemEvent: SystemEvent =>
        val params                               = systemEvent.paramSet
        val simulatorSentTimeParam: Parameter[_] = params.find(msg => msg.keyName == EventConstants.TIMESTAMP).get
        val simulatorPublishTime                 = simulatorSentTimeParam.head
        val hcdReceiveTime                       = params.find(msg => msg.keyName == EventConstants.HCD_EventReceivalTime).get.head
        val assemblyRecTime                      = params.find(msg => msg.keyName == EventConstants.ASSEMBLY_EVENT_RECEIVAL_TIME).get.head
        println(s"Health, $simulatorPublishTime, $hcdReceiveTime, $assemblyRecTime, $clientAppRecTime")
    }
    Future.successful[String]("Successfully processed Health event from assembly")
  }
  def processCurrentPosition(event: Event): Future[_] = {
    val clientAppRecTime = System.currentTimeMillis()
    // println(s"** Received current position Event : ${event} at client app receival time is : ${today} ** ")
    event match {
      case systemEvent: SystemEvent =>
        val params                               = systemEvent.paramSet
        val azPosParam: Parameter[_]             = params.find(msg => msg.keyName == EventConstants.POINTING_KERNEL_AZ_POS).get
        val elPosParam: Parameter[_]             = params.find(msg => msg.keyName == EventConstants.POINTING_KERNEL_EL_POS).get
        val simulatorSentTimeParam: Parameter[_] = params.find(msg => msg.keyName == EventConstants.TIMESTAMP).get
        val simulatorPublishTime                 = simulatorSentTimeParam.head
        val hcdReceiveTime                       = params.find(msg => msg.keyName == EventConstants.HCD_EventReceivalTime).get.head
        val assemblyRecTime                      = params.find(msg => msg.keyName == EventConstants.ASSEMBLY_EVENT_RECEIVAL_TIME).get.head
        println(
          s"CurrentPosition:, $azPosParam, $elPosParam,  $simulatorPublishTime,$hcdReceiveTime, $assemblyRecTime,$clientAppRecTime"
        )
    }
    Future.successful[String]("Successfully processed Current position event from assembly")
  }

  def sendDummyImmediateCommand()(implicit ec: ExecutionContext): Future[CommandResponse] = {
    val dummyImmediate = Setup(prefix, CommandName("DummyImmediate"), None)
    val commandService = getAssembly
    commandService.submit(dummyImmediate)
  }
  def sendDummyLongCommand()(implicit ex: ExecutionContext): Future[CommandResponse] = {
    val commandService = getAssembly
    val dummyLong      = Setup(prefix, CommandName("DummyLong"), None)
    commandService.submit(dummyLong)
  }

  def sendStartupCommand()(implicit ec: ExecutionContext): Future[CommandResponse] = {
    val commandService = getAssembly
    val setup          = Setup(prefix, CommandName("Startup"), None)
    commandService.submit(setup)
  }
  def sendShutDownCmd()(implicit ec: ExecutionContext): Future[CommandResponse] = {
    val commandService = getAssembly
    val setup          = Setup(prefix, CommandName("ShutDown"), None)
    commandService.submit(setup)
  }
  def sendDatumCommand(): Future[CommandResponse] = {
    val datumParam: Parameter[String] = axesKey.set("BOTH")
    val setup                         = Setup(prefix, CommandName("Datum"), None).add(datumParam)
    val commandService                = getAssembly
    commandService.submit(setup)
  }
  def sendFollowCommand(): Future[CommandResponse] = {
    val setup          = Setup(prefix, CommandName("Follow"), None)
    val commandService = getAssembly
    commandService.submit(setup)
  }
  def sendSimulationModeCommand(simulationMode: String): Future[CommandResponse] = {
    val simulationModeKey: Key[String]    = KeyType.StringKey.make("SimulationMode")
    val simulModeParam: Parameter[String] = simulationModeKey.set(simulationMode)
    val commandService                    = getAssembly
    val setup                             = Setup(prefix, CommandName("setSimulationMode"), None).add(simulModeParam)
    commandService.submit(setup)
  }
  def sendMoveCommand(): Future[CommandResponse] = {
    val axesParam: Parameter[String] = axesKey.set("BOTH")
    val azParam: Parameter[Double]   = azKey.set(1.5)
    val elParam: Parameter[Double]   = elKey.set(10)
    val commandService               = getAssembly
    val setup = Setup(prefix, CommandName("Move"), None)
      .add(axesParam)
      .add(azParam)
      .add(elParam)
    commandService.submit(setup)
  }

}
