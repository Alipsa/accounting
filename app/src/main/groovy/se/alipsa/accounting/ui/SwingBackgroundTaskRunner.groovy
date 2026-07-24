package se.alipsa.accounting.ui

import javax.swing.SwingUtilities

/** Runs blocking work on a daemon thread and reports on Swing's EDT. */
final class SwingBackgroundTaskRunner implements BackgroundTaskRunner {
  @Override
  void run(Closure backgroundWork, Closure onDone, Closure onError) {
    Thread thread = new Thread({
      try { def result = backgroundWork.call(); SwingUtilities.invokeLater { onDone.call(result) } }
      catch (Exception exception) { SwingUtilities.invokeLater { onError.call(exception) } }
    } as Runnable, 'ai-workspace-background')
    thread.daemon = true
    thread.start()
  }
}
