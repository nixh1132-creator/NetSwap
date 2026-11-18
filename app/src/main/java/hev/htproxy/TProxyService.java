package hev.htproxy;

/**
 * JNI binding for the pinned HevSocks5Tunnel native library.
 *
 * Keep this package/class and method names aligned with upstream's
 * JNI_OnLoad registration (PKGNAME=hev/htproxy, CLSNAME=TProxyService).
 * The native library is built in CI from a pinned upstream commit.
 */
public final class TProxyService {
    private TProxyService() {}

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int tunFd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
