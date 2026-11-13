package se.alipsa.accounting.ui

import javax.swing.SwingUtilities

/** Runs actions on the Event Dispatch Thread while preserving runtime exception types. */
final class SwingThreading {

  static void runOnEdt(Closure action) {
    if (SwingUtilities.isEventDispatchThread()) {
      action.call()
      return
    }
    try {
      SwingUtilities.invokeAndWait(action as Runnable)
    } catch (java.lang.reflect.InvocationTargetException exception) {
      Throwable cause = exception.cause
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause
      }
      throw new IllegalStateException(cause?.message ?: exception.message, cause)
    }
  }
}
