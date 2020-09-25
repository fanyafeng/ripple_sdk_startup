package com.ripple.sdk.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.os.TraceCompat
import com.ripple.sdk.startup.exception.StartupException
import com.ripple.sdk.startup.log.StartupLogger
import com.ripple.sdk.startup.provider.InitializationProvider
import java.util.*

/**
 * Author: fanyafeng
 * Date: 2020/9/25 10:09
 * Email: fanyafeng@live.cn
 * Description:
 */
class AppInitializer internal constructor(context: Context) {
    private val mInitialized: MutableMap<Class<*>?, Any?>
    private val mDiscovered: MutableSet<Class<out Initializer<*>?>>

    /**
     * 获取application的上下文
     */
    private val mContext: Context = context.applicationContext

    /**
     * Creates an instance of [AppInitializer]
     *
     * @param context The application context
     */
    init {
        mDiscovered = HashSet()
        mInitialized = HashMap()
    }

    companion object {
        // Tracing
        private const val SECTION_NAME = "Startup"

        /**
         * The [AppInitializer] instance.
         *
         * AppInitializer实例
         */
        @Volatile
        private var sInstance: AppInitializer? = null

        /**
         * Guards app initialization.
         *
         * app initialization 守卫
         */
        private val sLock = Any()

        /**
         * @param context The Application [Context]
         * @return The instance of [AppInitializer] after initialization.
         *
         * 单例，双check确定获取的是同一个实例
         */
        fun getInstance(context: Context): AppInitializer {
            if (sInstance == null) {
                synchronized(sLock) {
                    if (sInstance == null) {
                        sInstance = AppInitializer(context)
                    }
                }
            }
            return sInstance!!
        }
    }

    /**
     * Initializes a [Initializer] class type.
     *
     * @param component The [Class] of [Initializer] to initialize.
     * @param <T>       The instance type being initialized
     * @return The initialized instance
    </T> */
    fun <T> initializeComponent(component: Class<out Initializer<T?>?>): T {
        return doInitialize(component, HashSet())
    }

    /**
     * Returns `true` if the [Initializer] was eagerly initialized..
     *
     * @param component The [Initializer] class to check
     * @return `true` if the [Initializer] was eagerly initialized.
     */
    fun isEagerlyInitialized(component: Class<out Initializer<*>?>): Boolean {
        // If discoverAndInitialize() was never called, then nothing was eagerly initialized.
        return mDiscovered.contains(component)
    }

    fun <T> doInitialize(
        component: Class<out Initializer<*>?>,
        initializing: MutableSet<Class<*>?>
    ): T {
        synchronized(sLock) {
            val isTracingEnabled = TraceCompat.isEnabled()
            return try {
                if (isTracingEnabled) {
                    // Use the simpleName here because section names would get too big otherwise.
                    TraceCompat.beginSection(component.simpleName)
                }
                if (initializing.contains(component)) {
                    val message = String.format(
                        "Cannot initialize %s. Cycle detected.", component.name
                    )
                    throw IllegalStateException(message)
                }
                val result: Any?
                if (!mInitialized.containsKey(component)) {
                    initializing.add(component)
                    try {
                        val instance: Any? = component.getDeclaredConstructor().newInstance()
                        val initializer = instance as Initializer<*>?
                        val dependencies = initializer!!.dependencies()
                        if (!dependencies.isEmpty()) {
                            for (clazz in dependencies) {
                                if (!mInitialized.containsKey(clazz)) {
                                    doInitialize<Any>(clazz!!, initializing)
                                }
                            }
                        }
                        if (StartupLogger.DEBUG) {
                            StartupLogger.i(String.format("Initializing %s", component.name))
                        }
                        result = initializer.create(mContext)
                        if (StartupLogger.DEBUG) {
                            StartupLogger.i(String.format("Initialized %s", component.name))
                        }
                        initializing.remove(component)
                        mInitialized[component] = result
                    } catch (throwable: Throwable) {
                        throw StartupException(throwable)
                    }
                } else {
                    result = mInitialized[component]
                }
                result as T
            } finally {
                TraceCompat.endSection()
            }
        }
    }

    fun discoverAndInitialize() {
        try {
            TraceCompat.beginSection(SECTION_NAME)
            val provider = ComponentName(
                mContext.packageName,
                InitializationProvider::class.java.name
            )
            val providerInfo = mContext.packageManager
                .getProviderInfo(provider, PackageManager.GET_META_DATA)
            val metadata = providerInfo.metaData
            val startup = mContext.getString(R.string.androidx_startup)
            if (metadata != null) {
                val initializing: MutableSet<Class<*>?> = HashSet()
                val keys = metadata.keySet()
                for (key in keys) {
                    val value = metadata.getString(key, null)
                    if (startup == value) {
                        val clazz = Class.forName(key)
                        if (Initializer::class.java.isAssignableFrom(clazz)) {
                            val component = clazz as Class<out Initializer<*>?>
                            mDiscovered.add(component)
                            if (StartupLogger.DEBUG) {
                                StartupLogger.i(String.format("Discovered %s", key))
                            }
                            doInitialize<Any>(component, initializing)
                        }
                    }
                }
            }
        } catch (exception: PackageManager.NameNotFoundException) {
            throw StartupException(exception)
        } catch (exception: ClassNotFoundException) {
            throw StartupException(exception)
        } finally {
            TraceCompat.endSection()
        }
    }

}