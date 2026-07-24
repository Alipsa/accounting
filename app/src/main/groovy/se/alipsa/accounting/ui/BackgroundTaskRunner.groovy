package se.alipsa.accounting.ui

/** Runs background work and applies either its result or error. */
interface BackgroundTaskRunner { void run(Closure backgroundWork, Closure onDone, Closure onError) }
