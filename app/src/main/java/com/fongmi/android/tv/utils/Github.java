package com.fongmi.android.tv.utils;

import org.json.JSONArray;
import org.json.JSONObject;

public class Github {

    private static final String REPO = "FongMi/Release";
    private static final String UPDATE_MANIFEST = "https://media-repo.pages.dev/Release/android";
    private static final String UPDATE_APK = "https://pub-8198366a932249098157e9d7ef20af6a.r2.dev/Release/android";

    public static String getReleases() {
        return "https://api.github.com/repos/" + REPO + "/releases?per_page=100";
    }

    public static JSONObject getUpdate(JSONArray releases, String currentVersion, String filename) {
        int[] latest = version(currentVersion);
        if (latest == null) throw new IllegalArgumentException("Invalid app version");
        JSONObject selected = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease") || getAsset(release, filename) == null) continue;
            int[] candidate = version(release.optString("tag_name"));
            if (candidate == null) continue;
            if (isNewer(candidate, latest)) {
                latest = candidate;
                selected = release;
            }
        }
        return selected;
    }

    private static boolean isNewer(int[] candidate, int[] current) {
        for (int i = 0; i < current.length; i++) if (candidate[i] != current[i]) return candidate[i] > current[i];
        return false;
    }

    private static int[] version(String tag) {
        String[] parts = (tag.startsWith("v") ? tag.substring(1) : tag).split("\\.", -1);
        if (parts.length != 3) return null;
        int[] version = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("[0-9]+")) return null;
                version[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return version;
    }

    public static String getNotes(String body, String flavor) {
        String heading = "leanback".equals(flavor) ? "# 電視版 " : "# 手機版 ";
        StringBuilder notes = new StringBuilder();
        boolean hasSections = false;
        boolean selected = false;
        for (String line : body.split("\\r?\\n")) {
            String text = line.trim();
            if (text.startsWith("# 電視版 ") || text.startsWith("# 手機版 ")) {
                hasSections = true;
                selected = text.startsWith(heading);
            } else if (text.startsWith("# ")) {
                selected = false;
            } else if (selected && !text.isEmpty()) {
                appendNote(notes, text);
            }
        }
        return hasSections ? notes.toString() : body.trim();
    }

    private static void appendNote(StringBuilder notes, String text) {
        if (notes.length() > 0) notes.append('\n');
        if (text.startsWith("* ") || text.startsWith("- ")) text = "• " + text.substring(2);
        notes.append(text);
    }

    public static JSONObject getAsset(JSONObject release, String name) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && name.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))) return asset;
        }
        return null;
    }

    /**
     * 获取自建更新清单地址。
     *
     * @param name 清单文件名，格式为 flavor-abi
     * @return 清单 JSON 的完整地址
     */
    public static String getUpdateManifest(String name) {
        return UPDATE_MANIFEST + "/" + name + ".json";
    }

    /**
     * 获取自建安装包地址。
     *
     * @param name 安装包文件名，格式为 flavor-abi
     * @return APK 的完整地址
     */
    public static String getUpdateApk(String name) {
        return UPDATE_APK + "/" + name + ".apk";
    }
}
