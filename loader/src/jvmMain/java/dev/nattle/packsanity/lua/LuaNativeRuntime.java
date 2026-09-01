package dev.nattle.packsanity.lua;

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
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(chunkName, "chunkName");
        throwOnError(nativeRegisterModule(handle, name, source, chunkName));
    }

    public synchronized void registerPackFile(String path, byte[] contents) {
        checkOpen();
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(contents, "contents");
        throwOnError(nativeRegisterPackFile(handle, path, contents));
    }

    public synchronized void execute(String source, String chunkName) {
        checkOpen();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(chunkName, "chunkName");
        throwOnError(nativeExecute(handle, source, chunkName));
    }

    public synchronized void setProviderCount(String code, int count) {
        checkOpen();
        Objects.requireNonNull(code, "code");
        throwOnError(nativeSetProviderCount(handle, code, count));
    }

    public synchronized boolean callBoolean(String functionName, String argument) {
        checkOpen();
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(argument, "argument");
        String result = nativeCallBoolean(handle, functionName, argument);
        if (result == null) {
            throw new IllegalStateException("Lua call returned no result");
        }
        if (result.startsWith("error:")) {
            throw new IllegalStateException(result.substring("error:".length()));
        }
        return "true".equals(result);
    }

    public synchronized int callInteger(String functionName, String argument) {
        checkOpen();
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(argument, "argument");
        String result = nativeCallInteger(handle, functionName, argument);
        if (result == null) {
            throw new IllegalStateException("Lua call returned no result");
        }
        if (result.startsWith("error:")) {
            throw new IllegalStateException(result.substring("error:".length()));
        }
        return Integer.parseInt(result);
    }

    public synchronized String callString(String functionName, String argument) {
        checkOpen();
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(argument, "argument");
        String result = nativeCallString(handle, functionName, argument);
        if (result == null) {
            throw new IllegalStateException("Lua call returned no result");
        }
        if (result.startsWith("error:")) {
            throw new IllegalStateException(result.substring("error:".length()));
        }
        return result;
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

    private static void throwOnError(String error) {
        if (error != null) {
            throw new IllegalStateException(error);
        }
    }

    private static native long nativeCreate(long memoryLimitBytes, long instructionLimit);
    private static native void nativeBeginRefresh(long handle, long instructionLimit);
    private static native void nativeEndRefresh(long handle);
    private static native void nativeClose(long handle);
    private static native String nativeRegisterModule(long handle, String name, String source, String chunkName);
    private static native String nativeRegisterPackFile(long handle, String path, byte[] contents);
    private static native String nativeExecute(long handle, String source, String chunkName);
    private static native String nativeSetProviderCount(long handle, String code, int count);
    private static native String nativeCallBoolean(long handle, String functionName, String argument);
    private static native String nativeCallInteger(long handle, String functionName, String argument);
    private static native String nativeCallString(long handle, String functionName, String argument);
}
