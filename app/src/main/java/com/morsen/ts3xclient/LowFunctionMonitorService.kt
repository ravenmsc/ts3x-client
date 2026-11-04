package com.morsen.ts3xclient

/**
 * Compatibility tombstone for projects upgraded from an older client build by copying
 * files over the old source tree.
 *
 * The current client no longer uses a persistent foreground service for Low function
 * mode. Monitoring is implemented by LowFunctionMonitorWorker (WorkManager).
 *
 * This intentionally contains no Service implementation. Its only purpose is
 * to overwrite the obsolete legacy LowFunctionMonitorService.kt when an
 * archive is extracted over an existing project instead of into a clean
 * directory.
 */
private const val LOW_FUNCTION_SERVICE_REMOVED =
    "Use LowFunctionMonitorWorker"
