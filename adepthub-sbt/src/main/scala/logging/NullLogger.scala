package logging

import org.slf4j.helpers.MarkerIgnoringBase
import org.slf4j.Logger

class NullLogger extends MarkerIgnoringBase with Logger {

  override def isErrorEnabled: Boolean = false
  override def isWarnEnabled: Boolean = false
  override def isInfoEnabled: Boolean = false
  override def isDebugEnabled: Boolean = false
  override def isTraceEnabled: Boolean = false

  override def trace(msg: String): Unit = {}

  override def warn(msg: String): Unit = {}

  override def warn(format: String, arg: scala.Any): Unit = {}

  override def warn(format: String, arguments: AnyRef*): Unit = {}

  override def warn(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {}

  override def warn(msg: String, t: Throwable): Unit = {}

  override def error(msg: String): Unit = {}

  override def error(format: String, arg: scala.Any): Unit = {}

  override def error(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {}

  override def error(format: String, arguments: AnyRef*): Unit = {}

  override def error(msg: String, t: Throwable): Unit = {}

  override def debug(msg: String): Unit = {}

  override def debug(format: String, arg: scala.Any): Unit = {}

  override def debug(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {}

  override def debug(format: String, arguments: AnyRef*): Unit = {}

  override def debug(msg: String, t: Throwable): Unit = {}

  override def trace(format: String, arg: scala.Any): Unit = {}

  override def trace(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {}

  override def trace(format: String, arguments: AnyRef*): Unit = {}

  override def trace(msg: String, t: Throwable): Unit = {}

  override def info(msg: String): Unit = {}

  override def info(format: String, arg: scala.Any): Unit = {}

  override def info(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {}

  override def info(format: String, arguments: AnyRef*): Unit = {}

  override def info(msg: String, t: Throwable): Unit = {}
}
