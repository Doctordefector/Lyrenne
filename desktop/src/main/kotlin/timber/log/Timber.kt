package timber.log

import org.slf4j.LoggerFactory

/**
 * Desktop shim for Android's Timber logging library.
 * Routes all log calls to SLF4J.
 */
object Timber {
    private val logger = LoggerFactory.getLogger("Innertube")

    /** Returns a tagged tree that logs with a named SLF4J logger. */
    fun tag(tag: String): Tree = Tree(LoggerFactory.getLogger(tag))

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

    class Tree(private val log: org.slf4j.Logger) {
        fun d(message: String, vararg args: Any?) {
            if (args.isEmpty()) log.debug(message) else log.debug(message, *args)
        }
        fun i(message: String, vararg args: Any?) {
            if (args.isEmpty()) log.info(message) else log.info(message, *args)
        }
        fun w(message: String, vararg args: Any?) {
            if (args.isEmpty()) log.warn(message) else log.warn(message, *args)
        }
        fun e(message: String, vararg args: Any?) {
            if (args.isEmpty()) log.error(message) else log.error(message, *args)
        }
        fun e(t: Throwable, message: String, vararg args: Any?) {
            if (args.isEmpty()) log.error(message, t) else log.error(String.format(message, *args), t)
        }
        fun w(t: Throwable, message: String, vararg args: Any?) {
            if (args.isEmpty()) log.warn(message, t) else log.warn(String.format(message, *args), t)
        }
    }
}
