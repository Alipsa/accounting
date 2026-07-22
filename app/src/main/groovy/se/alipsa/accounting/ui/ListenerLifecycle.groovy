package se.alipsa.accounting.ui

/**
 * Guards register/unregister calls for classes that subscribe to process-wide singletons
 * (I18n, ActiveCompanyManager) so the subscription is added at most once and removed at most
 * once. Swing's addNotify/removeNotify only fire once a component is attached to (or detached
 * from) a displayable window, which covers real usage but not components constructed and
 * inspected without ever being shown (e.g. headless UI tests) - those must call dispose()
 * explicitly.
 */
trait ListenerLifecycle {

  private boolean listenersRegistered = false

  abstract void doRegisterListeners()

  abstract void doUnregisterListeners()

  void registerListenersOnce() {
    if (listenersRegistered) {
      return
    }
    doRegisterListeners()
    listenersRegistered = true
  }

  void dispose() {
    if (!listenersRegistered) {
      return
    }
    doUnregisterListeners()
    listenersRegistered = false
  }
}
