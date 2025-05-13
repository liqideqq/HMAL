package icu.nullptr.hidemyapplist.xposed.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import icu.nullptr.hidemyapplist.common.Constants
import icu.nullptr.hidemyapplist.xposed.*
import java.util.concurrent.atomic.AtomicReference

@TargetApi(Build.VERSION_CODES.TIRAMISU)
class PmsHookTarget34(private val service: HMALService) : IFrameworkHook {

    companion object {
        private const val TAG = "HMAL-PHT34"
    }

    private val getPackagesForUidMethod by lazy {
        findMethod("com.android.server.pm.Computer") {
            name == "getPackagesForUid"
        }
    }

    private var hook: XC_MethodHook.Unhook? = null
    private var lastFilteredApp: AtomicReference<String?> = AtomicReference(null)

    @Suppress("UNCHECKED_CAST")
    override fun load() {
        hook = findMethod("com.android.server.pm.AppsFilterImpl", findSuper = true) {
            name == "shouldFilterApplication"
        }.hookBefore { param ->
            runCatching {
                val snapshot = param.args[0]
                val callingUid = param.args[1] as Int
                if (callingUid == Constants.UID_SYSTEM) return@hookBefore
                val callingApps = Utils.binderLocalScope {
                    getPackagesForUidMethod.invoke(snapshot, callingUid) as Array<String>?
                } ?: return@hookBefore
                val targetApp = Utils.getPackageNameFromPackageSettings(param.args[3]) // PackageSettings <- PackageStateInternal
                for (caller in callingApps) {
                    if (service.shouldHide(caller, targetApp)) {
                        param.result = true
                        val last = lastFilteredApp.getAndSet(caller)
                        return@hookBefore
                    }
                }
            }.onFailure {
                unload()
            }
        }

        // Hook: PMS#getArchivedPackageInternal (QPR2+)
        exphook = findMethodOrNull("com.android.server.pm.PackageManagerService", findSuper = true) {
            name == "getArchivedPackageInternal"
        }?.hookBefore { param ->
            runCatching {
                val callingUid = Binder.getCallingUid()
                if (callingUid == Constants.UID_SYSTEM) return@hookBefore
                val callingApps = Utils.binderLocalScope {
                    service.pms.getPackagesForUid(callingUid)
                } ?: return@hookBefore
                val targetApp = param.args[0].toString()
                for (caller in callingApps) {
                    if (service.shouldHide(caller, targetApp)) {
                        param.result = null
                        service.filterCount++
                        val last = lastFilteredApp.getAndSet(caller)
                        return@hookBefore
                    }
                }
            }.onFailure {
                unload()
            }
        }
    }

    override fun unload() {
        hook?.unhook()
        hook = null
    }
}
