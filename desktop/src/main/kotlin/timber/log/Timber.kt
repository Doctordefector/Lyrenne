package timber.log

import org.slf4j.LoggerFactory

/**
 * Desktop shim for Android's Timber logging library.
 * Routes all log calls to SLF4J.
 */
object Timber {
    private val logger = LoggerFactory.getLogger("Innertube")

    fun d(message: String, vararg args: Any?) {
        if (args.isEmpty()) logger.debug(message)
        else logger.debug(message, *args)
    }

    fun i(message: String, vararg args: Any?) {
        if (args.isEmpty()) logger.info(message)
        else logger.info(message, *args)
    }

    fun w(message: String, vararg args: Any?) {
        if (args.isEmpty()) logger.warn(message)
        else logger.warn(message, *args)
    }

    fun e(message: String, vararg args: Any?) {
        if (args.isEmpty()) logger.error(message)
        else logger.error(message, *args)
    }

    fun e(t: Throwable, message: String, vararg args: Any?) {
        if (args.isEmpty()) logger.error(message, t)
        else logger.error(String.format(message, *args), t)
    }

    fun w(t: Throwable, message: String, vararg args: Any?) {
        if (args.isEmpty()) logger.warn(message, t)
        else logger.warn(String.format(message, *args), t)
    }
}
