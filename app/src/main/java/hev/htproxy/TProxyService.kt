package hev.htproxy

/**
 * JNI surface expected by the pinned HevSocks5Tunnel Android library.
 * The native library registers these methods from JNI_OnLoad.
 */
class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    external fun TProxyStartService(configPath: String, tunFd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray
}
