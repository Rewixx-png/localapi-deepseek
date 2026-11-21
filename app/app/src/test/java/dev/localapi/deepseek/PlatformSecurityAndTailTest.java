package dev.localapi.deepseek;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlatformSecurityAndTailTest {

    @Test
    public void sandboxProxyBlocksLoopbackAndPrivateRanges() throws Exception {
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("127.255.255.254")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("::1")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("0.0.0.0")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("10.0.2.2")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("172.16.0.5")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("169.254.169.254")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(SandboxProxy.isBlockedAddress(InetAddress.getByName("fc00::1")));

        assertFalse(SandboxProxy.isBlockedAddress(InetAddress.getByName("1.1.1.1")));
        assertFalse(SandboxProxy.isBlockedAddress(InetAddress.getByName("93.184.216.34")));
    }

    @Test
    public void sandboxTailReturnsTrailingBytesEvenWhenFileExceedsLimit() throws Exception {
        File tmp = File.createTempFile("sandbox-tail-test", ".log");
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                sb.append("line-").append(i).append("\n");
            }
            byte[] all = sb.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(all);
            }

            String tail = SandboxManager.tail(tmp, 256);
            assertEquals(256, tail.getBytes(StandardCharsets.UTF_8).length);
            assertTrue("tail should end with last line", tail.endsWith("line-4999\n"));

            String full = SandboxManager.tail(tmp, all.length + 100);
            assertEquals(sb.toString(), full);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Test
    public void updaterValidatesSha256AndAllowedHttpsHosts() {
        String validSha = "3ce80926960f48f5e9fd46fd2bf241ab05e71dfa920cec3181d87ddf66dcb427";
        assertTrue(Updater.isValidSha256(validSha));
        assertFalse(Updater.isValidSha256(""));
        assertFalse(Updater.isValidSha256("short"));
        assertFalse(Updater.isValidSha256("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));

        assertTrue(Updater.isAllowedDownloadUrl(
                "https://github.com/Rewixx-png/localapi-deepseek/releases/download/v1.0/LocalApiDeepSeek.apk"));
        assertTrue(Updater.isAllowedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/12345"));
        assertFalse(Updater.isAllowedDownloadUrl(
                "http://github.com/Rewixx-png/localapi-deepseek/releases/download/v1.0/LocalApiDeepSeek.apk"));
        assertFalse(Updater.isAllowedDownloadUrl(
                "https://evil.example.com/LocalApiDeepSeek.apk"));
    }
}
