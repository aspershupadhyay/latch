// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import android.os.IBinder

/**
 * Turns the phone's screen light off or on while the phone keeps running
 * (ADR-056), so the live mirror and the AI keep working with the screen dark.
 *
 * It is not called inside the app: the ADB shell starts it as its own process,
 * `CLASSPATH=<this APK> app_process / io.github.aspershupadhyay.latch.session.ScreenLight off`,
 * because only the shell user may set the display's power mode. This is the
 * same hidden `SurfaceControl.setDisplayPowerMode` call scrcpy makes. The
 * power button turns the screen fully on again at any time.
 */
object ScreenLight {
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    /** The shell runs this; prints one line and exits 0 on success. */
    @JvmStatic
    fun main(args: Array<String>) {
        val on = when (args.firstOrNull()) {
            "on" -> true
            "off" -> false
            else -> {
                System.err.println("usage: ScreenLight on|off")
                System.exit(2)
                return
            }
        }
        try {
            val tokens = displayTokens()
            if (tokens.isEmpty()) error("no built-in display found")
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            val setMode = surfaceControl.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            for (token in tokens) setMode.invoke(null, token, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
            println(if (on) "screen light on" else "screen light off; the phone keeps running")
            System.exit(0)
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            System.err.println("could not change the screen light: ${cause.javaClass.simpleName}: ${cause.message}")
            System.exit(1)
        }
    }

    /** The built-in displays' tokens: SurfaceControl before Android 14, DisplayControl from 14. */
    private fun displayTokens(): List<IBinder> {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        runCatching {
            val token = surfaceControl.getMethod("getInternalDisplayToken").invoke(null) as IBinder?
            if (token != null) return listOf(token)
        }
        runCatching {
            val ids = surfaceControl.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val get = surfaceControl.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val tokens = ids.toList().mapNotNull { get.invoke(null, it) as IBinder? }
            if (tokens.isNotEmpty()) return tokens
        }
        // Android 14+: the calls moved to DisplayControl in services.jar.
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
        )
        val loader = create.invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val displayControl = loader.loadClass("com.android.server.display.DisplayControl")
        val loadLibrary = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary.isAccessible = true
        loadLibrary.invoke(Runtime.getRuntime(), displayControl, "android_servers")
        val ids = displayControl.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
        val get = displayControl.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
        return ids.toList().mapNotNull { get.invoke(null, it) as IBinder? }
    }
}
