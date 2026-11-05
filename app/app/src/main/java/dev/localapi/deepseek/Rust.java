package dev.localapi.deepseek;

/** Every method returns a JSON envelope from deepseek-core: {"ok":…,"data":…}. */
public final class Rust {
    static {
        System.loadLibrary("localapi");
    }

    private Rust() {}

    public static native String nativeOpen(String filesDir);
    public static native String nativeStatus();
    public static native String nativeSetToken(String token, String mode);
    public static native String nativeRevalidate();
    public static native String nativeClearToken();
    public static native String nativeSetOnline(boolean online);
    public static native String nativeListChats(String kind);
    public static native String nativeNewChat(String kind);
    public static native String nativeGetChat(String id);
    public static native String nativeDeleteChat(String id);
    public static native String nativeSend(String chatId, String message, String model, String effort, boolean thinking, boolean search, String imagesJson, String systemPrompt);
    public static native String nativeGenerateTitle(String chatId, String text);
    public static native String nativePoll(long streamId);
    public static native String nativeCancel(long streamId);
    public static native String nativeStartServer(int port);
    public static native String nativeStopServer();
    public static native String nativeServerStatus();
}
