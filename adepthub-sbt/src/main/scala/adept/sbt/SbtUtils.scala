package adept.sbt

import sbt._

object SbtUtils {

  def evaluateTask[A](key: TaskKey[A], ref: ProjectRef, state: State): A =
    EvaluateTask(structure(state), key, state, ref, EvaluateTask.defaultConfig(state)) match {
      case Some((_, Value(a))) => a
      case Some((_, Inc(inc))) => throw new Exception(
        s"Error evaluating task '${key.key}': ${Incomplete.show(inc.tpe)}")
      case None => throw new Exception(s"Undefined task '${key.key}' for '${ref.project}'!")
    }

  def structure(state: State): BuildStructure = Project.extract(state).structure

  def currentProject(state: State) = Project.current(state)

  /**
   * Calculates which confs extends this conf because if we
   * change compile, we must update not only compile, but also
   * test and runtime becaus test extends (compile, runtime) and
   * runtime extends (test)
   *
   */
  def getAllExtendingConfig(logger: sbt.Logger, conf: sbt.Configuration, ivyConfigurations:
  Seq[sbt.Configuration]): Seq[sbt.Configuration] = {
    def getAllExtendingConfig(conf: sbt.Configuration, ivyConfigurations: Seq[sbt.Configuration]):
    Seq[sbt.Configuration] = {

      val current = ivyConfigurations.filter { descendant =>
        descendant.extendsConfigs.contains(conf)
      }
      current ++ current.flatMap(c => getAllExtendingConfig(c, ivyConfigurations))
    }
    
    val all = getAllExtendingConfig(conf, ivyConfigurations)
    all.filter { conf =>
      val internal = conf.name.endsWith("-internal")
      if (internal) logger.debug("Skipping internal configuration: " + conf) //TODO: <-is this right? 
      !internal
    }
  }

}
