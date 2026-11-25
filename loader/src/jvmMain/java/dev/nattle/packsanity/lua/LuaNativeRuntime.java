package dev.nattle.packsanity.lua;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class LuaNativeRuntime implements AutoCloseable {
    static {
        String nativeDirectory = System.getenv("PACKSANITY_NATIVE_DIR");
        if (nativeDirectory == null) {
            System.loadLibrary("packsanity_lua_jni");
        } else {
            System.load(new java.io.File(nativeDirectory, System.mapLibraryName("packsanity_lua_jni")).getAbsolutePath());
        }
    }

    private long handle;
    private boolean refreshActive;

    public LuaNativeRuntime(long memoryLimitBytes, long instructionLimit) {
        handle = nativeCreate(memoryLimitBytes, instructionLimit);
        if (handle == 0L) {
            throw new IllegalStateException("Could not create Lua 5.4 runtime");
        }
    }

    public synchronized void beginRefresh(long instructionLimit) {
        checkOpen();
        if (instructionLimit <= 0) throw new IllegalArgumentException("Refresh limit must be positive");
        if (refreshActive) throw new IllegalStateException("Lua refresh already active");
        nativeBeginRefresh(handle, instructionLimit);
        refreshActive = true;
    }

    public synchronized void endRefresh() {
        checkOpen();
        if (!refreshActive) throw new IllegalStateException("Lua refresh is not active");
        nativeEndRefresh(handle);
        refreshActive = false;
    }

    public synchronized void registerModule(String name, String source, String chunkName) {
        checkOpen();
        nativeRegisterModuleUtf8(handle, cString(name), cString(source), cString(chunkName));
    }

    public synchronized void registerPackFile(String path, byte[] contents) {
        checkOpen();
        nativeRegisterPackFileUtf8(handle, cString(path), Objects.requireNonNull(contents, "contents"));
    }

    public synchronized void execute(String source, String chunkName) {
        checkOpen();
        nativeExecuteUtf8(handle, cString(source), cString(chunkName));
    }

    public synchronized void setProviderCount(String code, int count) {
        checkOpen();
        nativeSetProviderCountUtf8(handle, cString(code), count);
    }

    public synchronized boolean callBoolean(String functionName, String argument) {
        checkOpen();
        return nativeCallNumber(handle, cString(functionName), argument.getBytes(StandardCharsets.UTF_8), true) != 0;
    }

    public synchronized int callInteger(String functionName, String argument) {
        checkOpen();
        return nativeCallNumber(handle, cString(functionName), argument.getBytes(StandardCharsets.UTF_8), false);
    }

    public synchronized String callString(String functionName, String argument) {
        checkOpen();
        return new String(
            nativeCallStringUtf8(handle, cString(functionName), argument.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8
        );
    }

    @Override
    public synchronized void close() {
        if (handle != 0L) {
            nativeClose(handle);
            handle = 0L;
        }
    }

    private void checkOpen() {
        if (handle == 0L) {
            throw new IllegalStateException("Lua runtime is closed");
        }
    }

    private static byte[] cString(String value) {
        if (value.indexOf(0) >= 0) throw new IllegalArgumentException("Lua source or name contains a NUL");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static native long nativeCreate(long memoryLimitBytes, long instructionLimit);
    private static native void nativeBeginRefresh(long handle, long instructionLimit);
    private static native void nativeEndRefresh(long handle);
    private static native void nativeClose(long handle);
    private static native void nativeRegisterModuleUtf8(long handle, byte[] name, byte[] source, byte[] chunkName);
    private static native void nativeRegisterPackFileUtf8(long handle, byte[] path, byte[] contents);
    private static native void nativeExecuteUtf8(long handle, byte[] source, byte[] chunkName);
    private static native void nativeSetProviderCountUtf8(long handle, byte[] code, int count);
    private static native int nativeCallNumber(long handle, byte[] functionName, byte[] argument, boolean booleanResult);
    private static native byte[] nativeCallStringUtf8(long handle, byte[] functionName, byte[] argument);
}
