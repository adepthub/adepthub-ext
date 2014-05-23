package adept.sbt

import sbt._

object SbtUtils {

  def evaluateTask[A](key: TaskKey[A], ref: ProjectRef, state: State): A =
    EvaluateTask(structure(state), key, state, ref, EvaluateTask defaultConfig state) match {
      case Some((_, Value(a))) => a
      case Some((_, Inc(inc))) => throw new Exception("Error evaluating task '%s': %s".format(key.key, Incomplete.show(inc.tpe)))
      case None => throw new Exception("Undefined task '%s' for '%s'!".format(key.key, ref.project))
    }

  def extracted(state: State): Extracted = Project.extract(state)

  def structure(state: State): BuildStructure = extracted(state).structure

  def currentProject(state: State) = Project.current(state)

}