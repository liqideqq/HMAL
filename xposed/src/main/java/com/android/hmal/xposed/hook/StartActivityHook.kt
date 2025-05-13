package com.android.hmal.xposed.hook

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import com.android.hmal.xposed.HMAService

class StartActivityHook(private val service: HMALService) : IFrameworkHook {

    companion object {
        private const val TAG = "StartActivityHook"
    }

    private val hooks = mutableListOf<XC_MethodHook.Unhook>()

    private fun Intent.isWebIntent(): Boolean {
        return Intent.ACTION_VIEW == action && data != null &&
                (data?.scheme.equals("http", ignoreCase = true) ||
                        data?.scheme.equals("https", ignoreCase = true))
    }

    override fun load() {
        val classesToHook = listOf(
            Context::class.java,
            Activity::class.java,
            ContextWrapper::class.java
        )

        val methodSignatures = listOf(
            arrayOf<Class<*>>(Intent::class.java),
            arrayOf<Class<*>>(Intent::class.java, Bundle::class.java),
            arrayOf<Class<*>>(Intent::class.java, Integer.TYPE),
            arrayOf<Class<*>>(Intent::class.java, Integer.TYPE, Bundle::class.java)
        )

        val methodNamesToHook = listOf("startActivity", "startActivityForResult")

        classesToHook.forEach { clazz ->
            methodNamesToHook.forEach { methodName ->
                methodSignatures.forEach { signature ->
                    runCatching {
                        val method = findMethodOrNull(clazz, true) {
                            this.name == methodName && this.parameterTypes.contentEquals(signature)
                        } ?: return@runCatching

                        hooks += method.hookBefore { param: XC_MethodHook.MethodHookParam ->
                            runCatching {
                                val context = param.thisObject as? Context ?: return@hookBefore
                                val callerPackageName = context.packageName
                                val intent = param.args[0] as? Intent ?: return@hookBefore

                                val appConfig = service.config.scope[callerPackageName]
                                val isCallerHooked = appConfig != null
                                val isInWhitelistMode = appConfig?.useWhitelist == true

                                if (!isCallerHooked) return@hookBefore
                                if (isInWhitelistMode) return@hookBefore

                                val targetPackageNameFromIntent: String? = intent.component?.packageName ?: intent.`package`
                                if (service.shouldHide(callerPackageName, targetPackageNameFromIntent)) {
                                    param.throwable = ActivityNotFoundException("No Activity found to handle $intent")
                                }
                            }.onFailure {
                                // 跳过
                            }
                        }
                    }.onFailure {
                        // 跳过
                    }
                }
            }
        }
    }

    override fun unload() {
        hooks.forEach { it.unhook() }
        hooks.clear()
    }
}
