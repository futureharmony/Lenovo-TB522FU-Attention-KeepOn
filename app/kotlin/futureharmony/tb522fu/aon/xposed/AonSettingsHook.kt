package futureharmony.tb522fu.aon.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Binder
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class AonSettingsHook : IXposedHookLoadPackage {
    private val callingToken = ThreadLocal<Long>()

    @SuppressLint("PrivateApi")
    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "com.android.settings" -> hookSettings(lpparam)
            "android" -> hookSystemServer(lpparam)
        }
    }

    private fun hookSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: Hooking com.android.settings...")

        // 1. Hook ColorOS KeepOnLookingController
        try {
            val kolClass = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.controller.KeepOnLookingController",
                lpparam.classLoader,
            )

            // Hook constructor to force permission flags to true
            XposedHelpers.findAndHookConstructor(kolClass, Context::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "mHasSufficientPermissions", true)
                        XposedHelpers.setBooleanField(param.thisObject, "mAonPermissions", true)
                        XposedBridge.log("$TAG: KeepOnLookingController permissions forced to true")
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            })

            // Hook isChecked to directly reflect Settings.Secure.adaptive_sleep
            XposedHelpers.findAndHookMethod(kolClass, "isChecked", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        if (ctx != null) {
                            val value = Settings.Secure.getInt(ctx.contentResolver, "adaptive_sleep", 0)
                            param.result = value == 1
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            })

            // Hook setChecked to store to Settings.Secure and avoid dialog / auto-reset
            XposedHelpers.findAndHookMethod(kolClass, "setChecked", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val checked = param.args[0] as Boolean
                            val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                            if (ctx != null) {
                                Settings.Secure.putInt(ctx.contentResolver, "adaptive_sleep", if (checked) 1 else 0)
                                Settings.Secure.putInt(ctx.contentResolver, "oplus_customize_smart_screen_off", if (checked) 1 else 0)
                                try {
                                    val sp = XposedHelpers.getObjectField(param.thisObject, "mKeepOnLookingSP") as? SharedPreferences
                                    sp?.edit()?.putBoolean("keep_on_looking", checked)?.apply()
                                } catch (_: Throwable) {
                                }
                            }
                            XposedBridge.log("$TAG: KeepOnLookingController setChecked($checked) applied")
                            param.result = true
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                })

            // Hook availabilityStatus
            XposedHelpers.findAndHookMethod(kolClass, "availabilityStatus", Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })

            // Hook isKeepOnLookingSupport
            XposedHelpers.findAndHookMethod(kolClass, "isKeepOnLookingSupport", Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })

            XposedBridge.log("$TAG: KeepOnLookingController successfully hooked!")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Error hooking KeepOnLookingController: ${t.message}")
        }

        // 2. Hook AOSP AdaptiveSleepPreferenceController
        try {
            val aospClass = XposedHelpers.findClass(
                "com.android.settings.display.AdaptiveSleepPreferenceController",
                lpparam.classLoader,
            )

            XposedHelpers.findAndHookMethod(aospClass, "hasSufficientPermission",
                android.content.pm.PackageManager::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })

            XposedHelpers.findAndHookMethod(aospClass, "isAdaptiveSleepSupported", Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })

            XposedHelpers.findAndHookMethod(aospClass, "isChecked", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        if (ctx != null) {
                            val value = Settings.Secure.getInt(ctx.contentResolver, "adaptive_sleep", 0)
                            param.result = value == 1
                        }
                    } catch (_: Throwable) {
                    }
                }
            })

            XposedBridge.log("$TAG: AdaptiveSleepPreferenceController successfully hooked!")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: AdaptiveSleepPreferenceController hook skipped/not found")
        }
    }

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: Hooking system_server...")

        // 1. Hook AttentionDetector in PowerManagerService
        try {
            val adClass = XposedHelpers.findClass(
                "com.android.server.power.AttentionDetector",
                lpparam.classLoader,
            )

            XposedHelpers.findAndHookMethod(adClass, "isAttentionServiceSupported", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })

            XposedBridge.log("$TAG: AttentionDetector.isAttentionServiceSupported forced to true")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: AttentionDetector hook info: ${t.message}")
        }

        // 2. Allow futureharmony.tb522fu.aon to call PowerManager.userActivity without security exception
        try {
            val pmsClass = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService\$BinderService",
                lpparam.classLoader,
            )

            XposedHelpers.findAndHookMethod(
                pmsClass,
                "userActivity",
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Elevate caller to SYSTEM so permission check passes seamlessly
                        callingToken.set(Binder.clearCallingIdentity())
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val token = callingToken.get()
                        if (token != null) {
                            callingToken.remove()
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: PowerManagerService.userActivity elevation hook applied!")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: PowerManagerService hook error: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "AonHook"
    }
}
