package org.antjs.runtime.demo;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/** Opens the user-controlled Android/OEM pages that affect long-running services. */
final class BackgroundPowerHelper {
    private BackgroundPowerHelper() {
    }

    static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return manager == null || manager.isIgnoringBatteryOptimizations(
                    context.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String statusText(Context context) {
        if (!isIgnoringBatteryOptimizations(context)) {
            return "系统正在对本应用执行电池优化，锁屏或切到后台后可能冻结 API。";
        }
        if (isStrictBackgroundBrand()) {
            return "系统电池优化已关闭。若后台仍中断，请在厂商设置中允许后台高耗电和自启动。";
        }
        return "系统电池优化已关闭；前台服务、CPU 唤醒锁和 Wi-Fi 锁均会随 API 启停。";
    }

    static String actionText(Context context) {
        if (!isIgnoringBatteryOptimizations(context)) return "允许后台运行";
        if (isStrictBackgroundBrand()) return "打开厂商后台设置";
        return "查看应用电池设置";
    }

    static String guideText() {
        if (isVivoLike()) return "vivo/iQOO：将后台耗电管理设为“允许后台高耗电”，并允许自启动。";
        if (isXiaomiLike()) return "小米/Redmi/POCO：允许自启动，并将省电策略设为“无限制”。";
        if (isOppoLike()) return "OPPO/realme：允许自启动、后台活动和后台高耗电。";
        if (isHuaweiLike()) return "华为/荣耀：在应用启动管理中关闭自动管理，允许后台活动。";
        if (isSamsungLike()) return "三星：把应用从“深度休眠应用”中移除并设为不受限制。";
        if (isOnePlusLike()) return "一加：允许自动启动和后台活动，并关闭电池优化。";
        if (isMeizuLike()) return "魅族：允许后台运行、自启动，并关闭智能省电限制。";
        return "不同厂商可能还有额外的后台活动或自启动限制。";
    }

    static boolean openNextStep(Activity activity) {
        if (!isIgnoringBatteryOptimizations(activity) && requestIgnoreBatteryOptimizations(activity)) {
            return true;
        }
        if (openVendorBackgroundSettings(activity)) return true;
        return openKnownActivity(activity,
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName())),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                new Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private static boolean requestIgnoreBatteryOptimizations(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return openKnownActivity(activity,
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + activity.getPackageName())),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    private static boolean openVendorBackgroundSettings(Context context) {
        String packageName = context.getPackageName();
        if (isVivoLike()) return openKnownActivity(context,
                new Intent("com.vivo.abe.permission.action.openhpactivity")
                        .setPackage("com.vivo.abe")
                        .putExtra("packageName", packageName)
                        .putExtra("pkgName", packageName)
                        .putExtra("packagename", packageName),
                new Intent().setComponent(new ComponentName("com.vivo.abe",
                        "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"))
                        .putExtra("packageName", packageName)
                        .putExtra("pkgName", packageName)
                        .putExtra("packagename", packageName),
                new Intent("com.vivo.abe.action.POWER_CONSUMPTION_MANAGER")
                        .setPackage("com.vivo.abe")
                        .putExtra("packageName", packageName)
                        .putExtra("pkgName", packageName),
                new Intent("com.vivo.abe.action.POWER_MANAGER")
                        .setPackage("com.vivo.abe")
                        .putExtra("packageName", packageName)
                        .putExtra("pkgName", packageName));
        if (isXiaomiLike()) return openKnownActivity(context,
                new Intent().setComponent(new ComponentName("com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                        .putExtra("package_name", packageName)
                        .putExtra("package_label", "FAnt 后端 Demo"),
                new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")));
        if (isOnePlusLike()) return openKnownActivity(context,
                new Intent().setComponent(new ComponentName("com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
                new Intent("com.android.settings.action.BACKGROUND_OPTIMIZE"));
        if (isOppoLike()) return openKnownActivity(context,
                new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                new Intent().setComponent(new ComponentName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity")),
                new Intent().setComponent(new ComponentName("com.heytap.safecenter",
                        "com.heytap.safecenter.startup.StartupAppListActivity")));
        if (isHuaweiLike()) return openKnownActivity(context,
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity")));
        if (isSamsungLike()) return openKnownActivity(context,
                new Intent().setComponent(new ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity")),
                new Intent().setComponent(new ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity")));
        if (isMeizuLike()) return openKnownActivity(context,
                new Intent().setComponent(new ComponentName("com.meizu.safe",
                        "com.meizu.safe.powerui.PowerAppPermissionActivity")),
                new Intent().setComponent(new ComponentName("com.meizu.safe",
                        "com.meizu.safe.permission.SmartBGActivity")));
        return false;
    }

    private static boolean openKnownActivity(Context context, Intent... intents) {
        for (Intent intent : intents) {
            try {
                if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isStrictBackgroundBrand() {
        return isVivoLike() || isXiaomiLike() || isOppoLike() || isHuaweiLike()
                || isSamsungLike() || isOnePlusLike() || isMeizuLike();
    }

    private static boolean isVivoLike() {
        String text = brandText();
        return text.contains("vivo") || text.contains("iqoo");
    }

    private static boolean isXiaomiLike() {
        String text = brandText();
        return text.contains("xiaomi") || text.contains("redmi") || text.contains("poco");
    }

    private static boolean isOppoLike() {
        String text = brandText();
        return text.contains("oppo") || text.contains("realme");
    }

    private static boolean isHuaweiLike() {
        String text = brandText();
        return text.contains("huawei") || text.contains("honor");
    }

    private static boolean isSamsungLike() {
        return brandText().contains("samsung");
    }

    private static boolean isOnePlusLike() {
        return brandText().contains("oneplus");
    }

    private static boolean isMeizuLike() {
        return brandText().contains("meizu");
    }

    private static String brandText() {
        return (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.PRODUCT)
                .toLowerCase(Locale.ROOT);
    }
}
