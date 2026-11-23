package io.iniwex.vowifix;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 轻量 Root Shell 执行器：通过 stdin 喂入脚本并配合共享 flock，避免命令注入与并发竞态。
 */
public final class RootShell {

    public static final String DATA_DIR = "/data/adb/vowifix";
    public static final String LOCK_PATH = DATA_DIR + "/diag.lock";
    public static final String LOG_PATH = DATA_DIR + "/service.log";
    public static final String PID_PATH = DATA_DIR + "/watcher.pid";
    public static final String DISABLED_FLAG = DATA_DIR + "/watcher.disabled";
    public static final String MODULE_DIR = "/data/adb/modules/vowifix";

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private RootShell() {
    }

    /**
     * 逐行接收 Root 命令标准输出的回调接口。
     */
    public interface LineConsumer {
        void onLine(String line);
    }

    /**
     * 以 Root 权限执行给定脚本，超限时强制终止并返回错误码。
     */
    public static Result execRoot(String script, int timeoutSec) {
        return execRootStreaming(script, timeoutSec, null);
    }

    /**
     * 以 Root 权限执行给定脚本并实时将 stdout 每一行回调给调用方。
     */
    public static Result execRootStreaming(String script, int timeoutSec, LineConsumer consumer) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "/system/bin/sh");
            proc = pb.start();

            try (OutputStream os = proc.getOutputStream()) {
                os.write(script.getBytes(StandardCharsets.UTF_8));
                if (!script.endsWith("\n")) {
                    os.write('\n');
                }
                os.write("exit $?\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            StringBuilder outBuf = new StringBuilder();
            StringBuilder errBuf = new StringBuilder();
            Thread outReader = startReaderThread(proc.getInputStream(), outBuf, consumer);
            Thread errReader = startReaderThread(proc.getErrorStream(), errBuf, null);

            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                joinQuietly(outReader);
                joinQuietly(errReader);
                return new Result(124, outBuf.toString().trim(), "命令执行超时 (" + timeoutSec + "s)");
            }

            joinQuietly(outReader);
            joinQuietly(errReader);
            return new Result(proc.exitValue(), outBuf.toString().trim(), errBuf.toString().trim());
        } catch (Throwable t) {
            if (proc != null) {
                proc.destroyForcibly();
            }
            return new Result(-1, "", t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /**
     * 快速检测当前是否已授予 Root 权限。
     */
    public static boolean checkRoot() {
        Result r = execRoot("id", 4);
        return r.isSuccess() && r.stdout.contains("uid=0");
    }

    /**
     * 解析 vowifix-efs 可执行文件路径（优先使用模块目录，兼容 PATH 挂载路径）。
     */
    public static String resolveBinarySnippet() {
        return "BIN=''\n"
                + "for cand in '" + MODULE_DIR + "/system/bin/vowifix-efs' '/system/bin/vowifix-efs' '/vendor/bin/vowifix-efs'; do\n"
                + "  if [ -x \"$cand\" ]; then BIN=\"$cand\"; break; fi\n"
                + "done\n";
    }

    /**
     * 使用 Toybox flock 的子 Shell 文件描述符重定向语法包裹临界区，保证与 service.sh 互斥。
     */
    public static String wrapWithDiagLock(String body) {
        return "mkdir -p '" + DATA_DIR + "' 2>/dev/null\n"
                + "(\n"
                + "  tries=0\n"
                + "  while ! /system/bin/flock -n 8 2>/dev/null; do\n"
                + "    tries=$((tries + 1))\n"
                + "    [ \"$tries\" -ge 20 ] && { echo 'DIAG_LOCK_BUSY'; exit 99; }\n"
                + "    sleep 1\n"
                + "  done\n"
                + body + "\n"
                + ") 8>'" + LOCK_PATH + "'\n";
    }

    private static Thread startReaderThread(
            final InputStream is, final StringBuilder target, final LineConsumer consumer) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (target.length() > 0) {
                        target.append('\n');
                    }
                    target.append(line);
                    if (consumer != null) {
                        try {
                            consumer.onLine(line);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        });
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(1500);
        } catch (InterruptedException ignored) {
        }
    }
}
