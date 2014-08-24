package adept.sbt.commands

import logging.SbtLogger
import sbt.State

abstract class AdeptCommand {
  def execute(state: State): State = {
    SbtLogger.withLogger(state.globalLogging.full) {
      realExecute(state)
    }
  }

  protected def realExecute(state: State): State
}
