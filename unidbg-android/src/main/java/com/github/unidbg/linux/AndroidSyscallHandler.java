package com.github.unidbg.linux;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.linux.file.EventFD;
import com.github.unidbg.linux.file.PipedReadFileIO;
import com.github.unidbg.linux.file.PipedWriteFileIO;
import com.github.unidbg.linux.signal.SigAction;
import com.github.unidbg.linux.signal.SignalFunction;
import com.github.unidbg.linux.signal.SignalTask;
import com.github.unidbg.linux.struct.StatFS;
import com.github.unidbg.linux.struct.StatFS32;
import com.github.unidbg.linux.struct.StatFS64;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.spi.SyscallHandler;
import com.github.unidbg.thread.MainTask;
import com.github.unidbg.thread.Task;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.thread.ThreadTask;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.UnixSyscallHandler;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import net.dongliu.apk.parser.utils.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

abstract class AndroidSyscallHandler extends UnixSyscallHandler<AndroidFileIO> implements SyscallHandler<AndroidFileIO> {

    private static final Log log = LogFactory.getLog(AndroidSyscallHandler.class);

    static final int MREMAP_MAYMOVE = 1;
    static final int MREMAP_FIXED = 2;

    private byte[] sched_cpu_mask;

    final long sched_setaffinity(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int cpusetsize = context.getIntArg(1);
        Pointer mask = context.getPointerArg(2);
        if (mask != null) {
            sched_cpu_mask = mask.getByteArray(0, cpusetsize);
        }
        if (log.isDebugEnabled()) {
            log.debug(Inspector.inspectString(sched_cpu_mask, "sched_setaffinity pid=" + pid + ", cpusetsize=" + cpusetsize + ", mask=" + mask));
        }
        return 0;
    }

    final long sched_getaffinity(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int cpusetsize = context.getIntArg(1);
        Pointer mask = context.getPointerArg(2);
        int ret = 0;
        if (mask != null && sched_cpu_mask != null) {
            mask.write(0, sched_cpu_mask, 0, cpusetsize);
            ret = cpusetsize;
        }
        if (log.isDebugEnabled()) {
            log.debug(Inspector.inspectString(sched_cpu_mask, "sched_getaffinity pid=" + pid + ", cpusetsize=" + cpusetsize + ", mask=" + mask));
        }
        return ret;
    }

    private static final int EFD_SEMAPHORE = 1;
    private static final int EFD_NONBLOCK = IOConstants.O_NONBLOCK;
    private static final int EFD_CLOEXEC = IOConstants.O_CLOEXEC;

    final int eventfd2(Emulator<?> emulator) {
        RegisterContext ctx = emulator.getContext();
        int initval = ctx.getIntArg(0);
        int flags = ctx.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("eventfd2 initval=" + initval + ", flags=0x" + Integer.toHexString(flags));
        }
        if ((flags & EFD_CLOEXEC) != 0) {
            throw new UnsupportedOperationException("eventfd2 flags=0x" + Integer.toHexString(flags));
        }
        boolean nonblock = (flags & EFD_NONBLOCK) != 0;
        boolean semaphore = (flags & EFD_SEMAPHORE) != 0;
        AndroidFileIO fileIO = new EventFD(initval, semaphore, nonblock);
        int minFd = this.getMinFd();
        this.fdMap.put(minFd, fileIO);
        if (verbose) {
            System.out.printf("eventfd(%d) with flags=0x%x fd=%d from %s%n", initval, flags, minFd, emulator.getContext().getLRPointer());
        }
        return minFd;
    }

    protected int sched_setscheduler(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int policy = context.getIntArg(1);
        Pointer param = context.getPointerArg(2);
        if (log.isDebugEnabled()) {
            log.debug("sched_setscheduler pid=" + pid + ", policy=" + policy + ", param=" + param);
        }
        return 0;
    }

    private static final int SCHED_OTHER = 0;

    protected int sched_getscheduler(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        if (log.isDebugEnabled()) {
            log.debug("sched_getscheduler pid=" + pid);
        }
        return SCHED_OTHER;
    }

    protected int sched_getparam(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        Pointer param = context.getPointerArg(1);
        if (log.isDebugEnabled()) {
            log.debug("sched_getparam pid=" + pid + ", param=" + param);
        }
        param.setInt(0, ANDROID_PRIORITY_NORMAL);
        return 0;
    }

    protected int sched_yield(Emulator<AndroidFileIO> emulator) {
        if (log.isDebugEnabled()) {
            log.debug("sched_yield");
        }
        if (emulator.getThreadDispatcher().getTaskCount() <= 1) {
            return 0;
        } else {
            throw new ThreadContextSwitchException().setReturnValue(0);
        }
    }

    private static final int ANDROID_PRIORITY_NORMAL = 0; /* most threads run at normal priority */

    protected int getpriority(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int which = context.getIntArg(0);
        int who = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("getpriority which=" + which + ", who=" + who);
        }
        return ANDROID_PRIORITY_NORMAL;
    }

    protected int setpriority(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int which = context.getIntArg(0);
        int who = context.getIntArg(1);
        int prio = context.getIntArg(2);
        if (log.isDebugEnabled()) {
            log.debug("setpriority which=" + which + ", who=" + who + ", prio=" + prio);
        }
        return 0;
    }

    protected int rt_sigtimedwait(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer uthese = context.getPointerArg(0);
        Pointer uinfo = context.getPointerArg(1);
        Pointer uts = context.getPointerArg(2);
        int sigsetsize = context.getIntArg(3);
        Task task = emulator.get(Task.TASK_KEY);
        log.info("rt_sigtimedwait uthese=" + uthese + ", uinfo=" + uinfo + ", uts=" + uts + ", sigsetsize=" + sigsetsize + ", task=" + task);
        return 0;
    }

    @Override
    protected FileResult<AndroidFileIO> createFdDir(int oflags, String pathname) {
        List<DirectoryFileIO.DirectoryEntry> list = new ArrayList<>();
        for (Map.Entry<Integer, AndroidFileIO> entry : fdMap.entrySet()) {
            list.add(new DirectoryFileIO.DirectoryEntry(DirectoryFileIO.DirentType.DT_LNK, entry.getKey().toString()));
        }
        return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname, list.toArray(new DirectoryFileIO.DirectoryEntry[0])));
    }

    @Override
    protected FileResult<AndroidFileIO> createTaskDir(Emulator<AndroidFileIO> emulator, int oflags, String pathname) {
        return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname, new DirectoryFileIO.DirectoryEntry(false, Integer.toString(emulator.getPid()))));
    }

    protected long statfs64(Emulator<AndroidFileIO> emulator, String path, Pointer buf) {
        FileResult<AndroidFileIO> result = resolve(emulator, path, IOConstants.O_RDONLY);
        if (result == null) {
            log.info("statfs64 buf=" + buf + ", path=" + path);
            emulator.getMemory().setErrno(UnixEmulator.ENOENT);
            return -1;
        }
        if (result.isSuccess()) {
            StatFS statFS = emulator.is64Bit() ? new StatFS64(buf) : new StatFS32(buf);
            int ret = result.io.statfs(statFS);
            if (ret != 0) {
                log.info("statfs64 buf=" + buf + ", path=" + path);
            } else {
                if (verbose) {
                    System.out.printf("File statfs '%s' from %s%n", result.io, emulator.getContext().getLRPointer());
                }
                if (log.isDebugEnabled()) {
                    log.debug("statfs64 buf=" + buf + ", path=" + path);
                }
            }
            return ret;
        } else {
            log.info("statfs64 buf=" + buf + ", path=" + path);
            emulator.getMemory().setErrno(result.errno);
            return -1;
        }
    }

    protected int pipe2(Emulator<?> emulator) {
        try {
            RegisterContext context = emulator.getContext();
            Pointer pipefd = context.getPointerArg(0);
            int flags = context.getIntArg(1);
            int writefd = getMinFd();
            Pair<AndroidFileIO, AndroidFileIO> pair = getPipePair(emulator, writefd);
            this.fdMap.put(writefd, pair.getLeft());
            int readfd = getMinFd();
            this.fdMap.put(readfd, pair.getRight());
            pipefd.setInt(0, readfd);
            pipefd.setInt(4, writefd);
            if (log.isDebugEnabled()) {
                log.debug("pipe2 pipefd=" + pipefd + ", flags=0x" + flags + ", readfd=" + readfd + ", writefd=" + writefd);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return 0;
    }

    protected Pair<AndroidFileIO, AndroidFileIO> getPipePair(Emulator<?> emulator, int writefd) throws IOException {
        PipedInputStream inputStream = new PipedInputStream();
        PipedOutputStream outputStream = new PipedOutputStream(inputStream);
        AndroidFileIO writeIO = new PipedWriteFileIO(outputStream, writefd);
        AndroidFileIO readIO = new PipedReadFileIO(inputStream, writefd);
        log.info("Return default pipe pair.");
        return new Pair<>(writeIO, readIO);
    }

    protected int fchmodat(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname_p = context.getPointerArg(1);
        int mode = context.getIntArg(2);
        int flags = context.getIntArg(3);
        String pathname = pathname_p.getString(0);
        if (log.isDebugEnabled()) {
            log.debug("fchmodat dirfd=" + dirfd + ", pathname=" + pathname + ", mode=0x" + Integer.toHexString(mode) + ", flags=0x" + Integer.toHexString(flags));
        }
        return 0;
    }

    protected int fchownat(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname_p = context.getPointerArg(1);
        int owner = context.getIntArg(2);
        int group = context.getIntArg(3);
        int flags = context.getIntArg(4);
        String pathname = pathname_p.getString(0);
        if (log.isDebugEnabled()) {
            log.debug("fchownat dirfd=" + dirfd + ", pathname=" + pathname + ", owner=" + owner + ", group=" + group + ", flags=0x" + Integer.toHexString(flags));
        }
        return 0;
    }

    protected int mkdirat(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname_p = context.getPointerArg(1);
        int mode = context.getIntArg(2);
        String pathname = pathname_p.getString(0);
        if (log.isDebugEnabled()) {
            log.debug("mkdirat dirfd=" + dirfd + ", pathname=" + pathname + ", mode=" + Integer.toHexString(mode));
        }
        if (dirfd != IO.AT_FDCWD) {
            throw new BackendException();
        }
        if (emulator.getFileSystem().mkdir(pathname, mode)) {
            if (log.isDebugEnabled()) {
                log.debug("mkdir pathname=" + pathname + ", mode=" + mode);
            }
            return 0;
        } else {
            log.info("mkdir pathname=" + pathname + ", mode=" + mode);
            emulator.getMemory().setErrno(UnixEmulator.EACCES);
            return -1;
        }
    }

    final int select(int nfds, Pointer checkfds, Pointer clearfds, boolean checkRead) {
        int count = 0;
        for (int i = 0; i < nfds; i++) {
            int mask = checkfds.getInt(i / 32);
            if(((mask >> i) & 1) == 1) {
                AndroidFileIO io = fdMap.get(i);
                if (!checkRead || io.canRead()) {
                    count++;
                } else {
                    mask &= ~(1 << i);
                    checkfds.setInt(i / 32, mask);
                }
            }
        }
        if (count > 0) {
            if (clearfds != null) {
                for (int i = 0; i < nfds; i++) {
                    clearfds.setInt(i / 32, 0);
                }
            }
        }
        return count;
    }

    final int sigaltstack(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer ss = context.getPointerArg(0);
        Pointer old_ss = context.getPointerArg(1);
        if (log.isDebugEnabled()) {
            log.debug("sigaltstack ss=" + ss + ", old_ss=" + old_ss);
        }
        return 0;
    }

    protected int renameat(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int olddirfd = context.getIntArg(0);
        String oldpath = context.getPointerArg(1).getString(0);
        int newdirfd = context.getIntArg(2);
        String newpath = context.getPointerArg(3).getString(0);
        int ret = emulator.getFileSystem().rename(oldpath, newpath);
        if (ret != 0) {
            log.info("renameat olddirfd=" + olddirfd + ", oldpath=" + oldpath + ", newdirfd=" + newdirfd + ", newpath=" + newpath);
        } else {
            log.debug("renameat olddirfd=" + olddirfd + ", oldpath=" + oldpath + ", newdirfd=" + newdirfd + ", newpath=" + newpath);
        }
        return 0;
    }

    protected int unlinkat(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname = context.getPointerArg(1);
        int flags = context.getIntArg(2);
        emulator.getFileSystem().unlink(pathname.getString(0));
        if (log.isDebugEnabled()) {
            log.info("unlinkat dirfd=" + dirfd + ", pathname=" + pathname.getString(0) + ", flags=" + flags);
        }
        return 0;
    }

    protected void exit(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int status = context.getIntArg(0);
        Task task = emulator.get(Task.TASK_KEY);
        if (task instanceof ThreadTask) {
            ThreadTask threadTask = (ThreadTask) task;
            threadTask.setExitStatus(status);
            throw new ThreadContextSwitchException().setReturnValue(0);
        }
        System.out.println("exit status=" + status);
        if (LogFactory.getLog(AbstractEmulator.class).isDebugEnabled()) {
            emulator.attach().debug();
        }
        emulator.getBackend().emu_stop();
    }

    private static final int SIGKILL = 9;
    private static final int SIGSTOP = 19;
    private static final int SIG_ERR = -1;

    private final Map<Integer, SigAction> sigActionMap = new HashMap<>();

    @Override
    public MainTask createSignalHandlerTask(Emulator<?> emulator, int sig) {
        SigAction action = sigActionMap.get(sig);
        if (action != null) {
            return new SignalFunction(emulator, sig, action);
        }
        return super.createSignalHandlerTask(emulator, sig);
    }

    @Override
    protected int sigaction(Emulator<?> emulator, int signum, Pointer act, Pointer oldact) {
        SigAction action = SigAction.create(emulator, act);
        SigAction oldAction = SigAction.create(emulator, oldact);
        if (log.isDebugEnabled()) {
            log.debug("sigaction signum=" + signum + ", action=" + action + ", oldAction=" + oldAction);
        }
        if (SIGKILL == signum || SIGSTOP == signum) {
            if (oldAction != null) {
                oldAction.sa_handler = UnidbgPointer.pointer(emulator, SIG_ERR);
                oldAction.pack();
            }
            return -UnixEmulator.EINVAL;
        }
        SigAction lastAction = sigActionMap.put(signum, action);
        if (oldAction != null) {
            if (lastAction == null) {
                oldact.write(0, new byte[oldAction.size()], 0, oldAction.size());
            } else {
                oldAction.sa_handler = lastAction.sa_handler;
                oldAction.sa_restorer = lastAction.sa_restorer;
                oldAction.setFlags(lastAction.getFlags());
                oldAction.setMask(lastAction.getMask());
                oldAction.pack();
            }
        }
        return 0;
    }

    protected int kill(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int sig = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("kill pid=" + pid + ", sig=" + sig);
        }
        Task task = emulator.get(Task.TASK_KEY);
        if (pid == 0 && sig > 0 && task != null) {
            SigAction action = sigActionMap.get(sig);
            if (action != null) {
                task.addSignalTask(new SignalTask(sig, action));
                throw new ThreadContextSwitchException().setReturnValue(0);
            }
            return 0;
        }
        throw new UnsupportedOperationException("kill pid=" + pid + ", sig=" + sig + ", LR=" + context.getLRPointer());
    }

    protected int tgkill(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int tgid = context.getIntArg(0);
        int tid = context.getIntArg(1);
        int sig = context.getIntArg(2);
        if (log.isDebugEnabled()) {
            log.debug("tgkill tgid=" + tgid + ", tid=" + tid + ", sig=" + sig);
        }
        Task task = emulator.get(Task.TASK_KEY);
        if (sig > 0 && task != null) {
            SigAction action = sigActionMap.get(sig);
            if (action != null) {
                task.addSignalTask(new SignalTask(sig, action));
                throw new ThreadContextSwitchException().setReturnValue(0);
            }
        }
        return 0;
    }

}
