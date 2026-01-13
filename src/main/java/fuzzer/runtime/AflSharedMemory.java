package fuzzer.runtime;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * Minimal SysV shared memory helper to interact with AFL++ instrumentation.
 */
final class AflSharedMemory implements AutoCloseable {

    private interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int shmget(int key, NativeLong size, int shmflg);
        Pointer shmat(int shmid, Pointer addr, int shmflg);
        int shmdt(Pointer shmaddr);
        int shmctl(int shmid, int cmd, Pointer buf);
        int memset(Pointer dest, int c, NativeLong n);
    }

    private static final int IPC_PRIVATE = 0;
    private static final int IPC_CREAT = 01000;
    private static final int IPC_EXCL = 02000;
    private static final int IPC_RMID = 0;

    private final int shmid;
    private final int size;
    private Pointer addr;

    private AflSharedMemory(int shmid, int size, Pointer addr) {
        this.shmid = shmid;
        this.size = size;
        this.addr = addr;
    }

    static AflSharedMemory allocate(int size, int permissions) {
        int flags = IPC_CREAT | IPC_EXCL | permissions;
        int id = LibC.INSTANCE.shmget(IPC_PRIVATE, new NativeLong(size), flags);
        if (id == -1) {
            throw new IllegalStateException("shmget failed, errno=" + Native.getLastError());
        }
        Pointer ptr = LibC.INSTANCE.shmat(id, Pointer.NULL, 0);
        if (Pointer.nativeValue(ptr) == -1) {
            throw new IllegalStateException("shmat failed, errno=" + Native.getLastError());
        }
        // Ensure the map starts empty.
        LibC.INSTANCE.memset(ptr, 0, new NativeLong(size));
        return new AflSharedMemory(id, size, ptr);
    }

    int id() {
        return shmid;
    }

    byte[] snapshot() {
        return addr.getByteArray(0, size);
    }

    void clear() {
        LibC.INSTANCE.memset(addr, 0, new NativeLong(size));
    }

    @Override
    public void close() {
        if (addr != null) {
            LibC.INSTANCE.shmdt(addr);
            addr = null;
        }
        LibC.INSTANCE.shmctl(shmid, IPC_RMID, Pointer.NULL);
    }
}
