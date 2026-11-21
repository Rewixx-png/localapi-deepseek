package dev.localapi.deepseek;

import java.util.Locale;

/**
 * Single source of truth for repository URLs, ports, default model IDs,
 * sandbox asset metadata, and security allowlists shared by Java and Kotlin.
 */
public final class AppConfig {
    public static final String REPO_OWNER = "Rewixx-png";
    public static final String REPO_NAME = "localapi-deepseek";
    public static final String REPO_URL = "https://github.com/" + REPO_OWNER + "/" + REPO_NAME;
    public static final String TOKEN_DOCS_URL = REPO_URL + "/blob/main/docs/get-token.md";
    public static final String UPDATE_MANIFEST_URL =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME
                    + "/contents/update.json?ref=main";
    public static final String UPDATE_MANIFEST_FALLBACK =
            "https://raw.githubusercontent.com/" + REPO_OWNER + "/" + REPO_NAME
                    + "/main/update.json";

    public static final int DEFAULT_LOCAL_API_PORT = 8787;
    public static final int MIN_LOCAL_API_PORT = 1024;
    public static final int MAX_LOCAL_API_PORT = 65535;

    public static final int SANDBOX_PROXY_PORT = 18991;
    public static final String SANDBOX_GUEST_PROXY_HOST = "10.0.2.2";
    public static final String SANDBOX_GUEST_PROXY_URL =
            "http://" + SANDBOX_GUEST_PROXY_HOST + ":" + SANDBOX_PROXY_PORT;

    public static final String DEFAULT_MODEL_ID = "deepseek-flash";
    public static final String PRO_MODEL_ID = "deepseek-v4-pro";
    public static final String DEFAULT_MODEL_LABEL = "DeepSeek V4.1 Flash 1017";
    public static final String DEFAULT_SEARCH_URL_PREFIX = "https://duckduckgo.com/?q=";

    public static final String SANDBOX_VERSION = "ubuntu-disk-1";
    public static final String SANDBOX_ASSET_URL =
            REPO_URL + "/releases/download/sandbox-" + SANDBOX_VERSION + "/ubuntu-sandbox.zip";
    public static final String SANDBOX_ASSET_SHA256 =
            "22d2c6d4feba7d514e1406ce3ad394daa61b98fcd72e47fee01650c16f0775b6";
    public static final long SANDBOX_ASSET_SIZE = 132_758_428L;
    public static final long SANDBOX_MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;

    private AppConfig() {
    }

    public static boolean isAllowedUpdateHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
        return h.equals("github.com")
                || h.equals("objects.githubusercontent.com")
                || h.equals("release-assets.githubusercontent.com")
                || h.endsWith(".githubusercontent.com");
    }
}
