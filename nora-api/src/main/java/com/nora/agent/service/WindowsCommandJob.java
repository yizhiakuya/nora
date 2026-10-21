package com.nora.agent.service;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/** Windows 原生任务归属；关闭句柄会终止任务内所有进程，包括父 shell 已退出的后代。 */
final class WindowsCommandJob implements AutoCloseable {
    interface Kernel extends StdCallLibrary {
        Kernel API = Native.load("kernel32", Kernel.class);
        Pointer CreateJobObjectW(Pointer attributes, WString name);
        Pointer OpenProcess(int access, boolean inherit, int pid);
        boolean AssignProcessToJobObject(Pointer job, Pointer process);
        boolean SetInformationJobObject(Pointer job, int kind, Pointer info, int length);
        boolean CloseHandle(Pointer handle);
        int GetLastError();
    }
    private Pointer job;

    WindowsCommandJob(long pid) {
        if (Native.POINTER_SIZE != 8) throw new IllegalStateException("Windows 命令执行需要 64 位 Java");
        job = Kernel.API.CreateJobObjectW(null, null);
        if (job == null) throw error("CreateJobObject");
        try {
            // winnt.h x64 ABI: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = 144 bytes；LimitFlags 在偏移 16。
            // https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information
            try (Memory limits = new Memory(144)) {
                limits.clear(); limits.setInt(16, 0x2000); // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                if (!Kernel.API.SetInformationJobObject(job, 9, limits, (int) limits.size())) throw error("SetInformationJobObject");
            }
            Pointer process = Kernel.API.OpenProcess(0x0100 | 0x0001, false, Math.toIntExact(pid)); // SET_QUOTA | TERMINATE
            if (process == null) throw error("OpenProcess");
            try { if (!Kernel.API.AssignProcessToJobObject(job, process)) throw error("AssignProcessToJobObject"); }
            finally { Kernel.API.CloseHandle(process); }
        } catch (RuntimeException e) { close(); throw e; }
    }

    private static IllegalStateException error(String operation) {
        return new IllegalStateException(operation + " failed, Win32 error " + Kernel.API.GetLastError());
    }
    @Override public synchronized void close() {
        if (job == null) return;
        if (!Kernel.API.CloseHandle(job)) throw error("CloseHandle");
        job = null;
    }
}
