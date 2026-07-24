package se.alipsa.accounting.service

/** Test seam around environment-variable lookup. */
interface EnvironmentLookup { String getenv(String name) }
