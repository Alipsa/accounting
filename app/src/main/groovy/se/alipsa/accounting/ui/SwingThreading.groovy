package se.alipsa.accounting.ui

import javax.swing.SwingUtilities

/** Runs UI mutations on the Event Dispatch Thread while preserving validation errors. */
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
      if (cause instanceof IllegalArgumentException) {
        throw (IllegalArgumentException) cause
      }
      throw new IllegalStateException(cause?.message ?: exception.message, cause)
    }
  }
}
