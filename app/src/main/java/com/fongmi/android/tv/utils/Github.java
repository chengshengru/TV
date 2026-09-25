package com.fongmi.android.tv.utils;

public class Github {

    /**
     * 更新清单（JSON）源：Cloudflare Pages，文件直接位于 Release/android/ 根层下（无 apk/ 子目录）。
     * 例：https://media-repo.pages.dev/Release/android/leanback.json
     */
    public static final String URL = "https://media-repo.pages.dev/Release/android";

    /**
     * 安装包（APK）源：Cloudflare R2，仅作为清单未返回下载地址时的兜底。
     * 清单 JSON 若含非空 "url" 字段（支持 {flavor}/{abi} 占位符），则优先生效 —— 见 Updater.getApk()。
     * <p>
     * APK 体积 80~93 MiB，超过 Pages 单文件 25 MiB 上限，故与清单分开托管。
     */
    public static final String APK = "https://REPLACE-WITH-R2-DOMAIN/Release/android";

    public static String getJson(String name) {
        return URL + "/" + name + ".json";
    }

    public static String getApk(String name) {
        return APK + "/" + name + ".apk";
    }
}
