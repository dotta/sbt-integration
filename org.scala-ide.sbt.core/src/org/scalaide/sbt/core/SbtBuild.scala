package org.scalaide.sbt.core

import java.io.File

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise

import org.eclipse.core.resources.IProject
import org.eclipse.ui.console.MessageConsole
import org.scalaide.sbt.ui.console.ConsoleProvider

import sbt.protocol.ProjectReference

object SbtBuild {

  /** cache from path to SbtBuild instance */
  private var builds = immutable.Map[File, SbtClientProxy]()
  private val buildsLock = new Object

  /** Returns the SbtBuild instance for the given path */
  private def buildFor(buildRoot: File): SbtClientProxy = {
    buildsLock.synchronized {
      builds.get(buildRoot) match {
        case Some(build) =>
          build
        case None =>
          val build = SbtClientProxy(buildRoot)
          builds += buildRoot -> build
          build
      }
    }
  }

  /** Create and initialize a SbtBuild instance for the given path.*/
  def apply(buildRoot: File): SbtBuild =
    new SbtBuild(buildFor(buildRoot), ConsoleProvider(buildRoot))
}

class SbtBuild private (sbtClient: SbtClientProxy, console: MessageConsole) {
  /** Triggers the compilation of the given project.*/
  def compile(project: IProject): Unit = sbtClient.requestExecution(s"${project.getName}/compile")

  def projects(): Future[immutable.Seq[ProjectReference]] = {
    val p = Promise[immutable.Seq[ProjectReference]]
    val subscription = sbtClient.buildStructure.subscribe(_ match {
      case None => ()
      case Some(build) =>
        p.success(build.projects.to[immutable.Seq])
    })
    p.future.onComplete(_ => subscription.unsubscribe())
    p.future
  }
}