package dev.nattle.packsanity.ut;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Android JNI owner for one serialized embedded CPython/Universal Tracker session. */
public final class PythonNativeRuntime implements AutoCloseable {
    static {
        System.loadLibrary("packsanity_python");
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PackSanityUniversalTracker");
        thread.setDaemon(true);
        return thread;
    });
    private long handle;

    public PythonNativeRuntime(String pythonHome, String modulePath) {
        try {
            handle = onWorker(() -> nativeCreate(pythonHome, modulePath));
        } catch (RuntimeException | Error error) {
            worker.shutdown();
            throw error;
        }
        if (handle == 0) {
            worker.shutdown();
            throw new IllegalStateException("Could not create embedded CPython runtime");
        }
    }

    public String loadTrackerPackage(String requestJson) { return call(0, requestJson); }
    public String loadWorld(String requestJson) { return call(1, requestJson); }
    public String initializeTracker(String requestJson) { return call(2, requestJson); }
    public String updateState(String requestJson) { return call(3, requestJson); }

    @Override public synchronized void close() {
        try {
            if (handle != 0) {
                long activeHandle = handle;
                handle = 0;
                onWorker(() -> {
                    nativeClose(activeHandle);
                    return null;
                });
            }
        } finally {
            worker.shutdown();
        }
    }

    private synchronized String call(int operation, String requestJson) {
        long activeHandle = requireHandle();
        return onWorker(() -> nativeCall(activeHandle, operation, requestJson));
    }

    private long requireHandle() {
        if (handle == 0) throw new IllegalStateException("Embedded CPython runtime is closed");
        return handle;
    }

    private <T> T onWorker(CheckedSupplier<T> operation) {
        Future<T> pending = worker.submit(operation::get);
        boolean interrupted = false;
        try {
            // Native CPython calls cannot be safely cancelled. Keep ownership until completion,
            // especially when creation returns a handle or close tears down its interpreter.
            while (true) {
                try {
                    return pending.get();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Embedded CPython worker failed", cause);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get(); }

    private static native long nativeCreate(String pythonHome, String modulePath);
    private static native void nativeClose(long handle);
    private static native String nativeCall(long handle, int operation, String requestJson);
}
