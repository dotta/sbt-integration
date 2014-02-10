package org.scalaide.sbt.core

import java.io.File

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.tools.eclipse.logging.HasLogger

import com.typesafe.sbtrc.client.AbstractSbtServerLocator
import com.typesafe.sbtrc.client.SimpleConnector

import rx.lang.scala.Subject
import rx.lang.scala.subjects.BehaviorSubject
import sbt.client.EventListener
import sbt.client.SbtClient
import sbt.client.Subscription
import sbt.protocol._

private[core] class SbtClientProxy private (buildRoot: File) extends HasLogger {

  @volatile private var sbtClient: SbtClient = _

  // TODO: What do we do with the subscriptions?
  private val eventListeners = new TrieMap[EventListener, Subscription]
  @volatile private var sbtClientSubscription: Subscription = _
  @volatile private var buildSubscription: Subscription = _

  private def connect(): Unit = {
    val connector = new SimpleConnector(buildRoot, new SbtClientProxy.IDEServerLocator)
    val sbtClientSubscription = connector.onConnect { client =>
      def registerListeners(): Unit = {
        val toReinitialize = eventListeners.iterator
        for ((listener, subscription) <- toReinitialize) {
          subscription.cancel()
          eventListeners -= listener
          handleEvents(listener)
        }

        buildSubscription = sbtClient watchBuild {
          case b: MinimalBuildStructure => buildStructure.onNext(Some(b))
        }
      }
      sbtClient = client
      registerListeners()
    }
  }

  def handleEvents(listener: EventListener): Unit = {
    val subscription = sbtClient handleEvents listener
    eventListeners += listener -> subscription
  }

  def requestExecution(commandOrTask: String): Unit = sbtClient.requestExecution(commandOrTask, None) onFailure {
    case failure => logger.warn(s"Failed to execute `$commandOrTask`")
  }

  val buildStructure: Subject[Option[MinimalBuildStructure]] = BehaviorSubject(None)
}

private[core] object SbtClientProxy {
  /** SbtServerLocator returning the bundled sbtLaunch.jar and sbt-server.properties. */
  private class IDEServerLocator extends AbstractSbtServerLocator {
    override def sbtLaunchJar: java.io.File = SbtRemotePlugin.plugin.SbtLaunchJarLocation
    override def sbtProperties(directory: java.io.File): java.net.URL = SbtRemotePlugin.plugin.sbtProperties
  }

  def apply(buildRoot: File): SbtClientProxy = {
    val proxy = new SbtClientProxy(buildRoot)
    proxy.connect()
    proxy
  }
}