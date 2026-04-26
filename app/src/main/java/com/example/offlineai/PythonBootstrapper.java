package com.example.offlineai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.concurrent.CountDownLatch;

/**
 * Lazy Chaquopy Python bootstrapper.
 *
 * Problem background:
 *   Python.start() was previously called synchronously in GlobalApplication.onCreate(),
 *   which can take 10+ seconds on some devices (especially when the kernel-level
 *   userfaultfd / ART GC path stalls). That easily breaches the Application startup
 *   deadline and triggers an ANR ("failed to complete startup").
 *
 * Strategy:
 *   - Do NOT start Python at process creation time.
 *   - Call {@link #ensureStarted(Context)} just before the first Python usage
 *     (typically inside UnifiedActionExecutor.executePython).
 *   - Chaquopy's Python.start() is safest on the main thread; if the caller is on
 *     a worker thread, we post to the main looper and block the worker via a latch.
 *   - Guarded by a volatile flag + synchronized block so it only runs once per process.
 */
public final class PythonBootstrapper {
    private static final String TAG = "PythonBootstrapper";

    private static volatile boolean started = false;
    private static final Object lock = new Object();

    private PythonBootstrapper() {}

    /**
     * Block until Python is started. Safe to call from any thread. Idempotent.
     * Throws RuntimeException if initialization fails.
     */
    public static void ensureStarted(Context context) {
        if (started) return;
        // Fast path: another path already started Chaquopy (defensive check).
        if (Python.isStarted()) {
            started = true;
            return;
        }
        synchronized (lock) {
            if (started) return;
            if (Python.isStarted()) {
                started = true;
                return;
            }
            final Context appCtx = context.getApplicationContext();
            if (Looper.myLooper() == Looper.getMainLooper()) {
                doStart(appCtx);
            } else {
                // Worker thread: hop to main looper and wait.
                final CountDownLatch latch = new CountDownLatch(1);
                final Throwable[] err = new Throwable[1];
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        doStart(appCtx);
                    } catch (Throwable t) {
                        err[0] = t;
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    latch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for Python.start()", ie);
                }
                if (err[0] != null) {
                    throw new RuntimeException("Python.start failed on main thread", err[0]);
                }
            }
        }
    }

    private static void doStart(Context appCtx) {
        long t0 = System.currentTimeMillis();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(appCtx));
        }
        started = true;
        Log.i(TAG, "[PYTHON_BOOT] Python.start finished, costMs=" + (System.currentTimeMillis() - t0));
    }

    /** For diagnostics / UI. */
    public static boolean isStarted() {
        return started || Python.isStarted();
    }
}
