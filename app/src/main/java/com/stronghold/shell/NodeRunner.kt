package com.stronghold.shell

import android.content.Context
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * 通过 JNA 直接调用 libnode.so 的 `node::Start(int, char**)` 启动 Node。
 *
 * 原理（与现有壳一致）：
 *   1. System.loadLibrary("node")  载入 libnode.so
 *   2. JNA NativeLibrary.getInstance("node").getFunction("_ZN4node5StartEiPPc")
 *      拿到 node::Start 的函数指针
 *   3. 构造 argv（[可执行名, 入口脚本, ...]），用 Function.invokeInt 调用
 *
 *   C++ mangled name：node::Start(int, char**)  →  _ZN4node5StartEiPPc
 */
object NodeRunner {

    private const val TAG = "StrongholdShell"
    private const val START_SYMBOL = "_ZN4node5StartEiPPc"

    @Volatile
    private var running = false

    private var nodeThread: Thread? = null

    /**
     * 启动 Node。会阻塞在单独线程里跑事件循环。
     * @param bundleDir Node 项目根目录（含 main.js）
     * @param entryName 入口脚本文件名
     * @param logFile   输出重定向日志文件（null 则只到 logcat）
     */
    fun start(context: Context, bundleDir: String, entryName: String, logFile: File?) {
        if (running) {
            Log.w(TAG, "Node 已在运行，忽略重复启动")
            return
        }

        nodeThread = Thread {
            try {
                // 1) 载入 libnode.so
                System.loadLibrary("node")
                Log.i(TAG, "libnode.so 载入成功")

                // 2) 拿到 node::Start 函数指针
                val nl = NativeLibraryHolder.get()
                val startFn = nl.getFunction(START_SYMBOL)
                Log.i(TAG, "已获取 $START_SYMBOL")

                // 3) 构造 argv
                val argv = arrayOf(
                    entryName,              // argv[0]
                    "--root=$bundleDir"     // 告诉 main.js 自己的根目录
                )
                val argvMem = com.sun.jna.Memory(8L * argv.size.toLong())
                for (i in argv.indices) {
                    argvMem.setPointer((i * 8).toLong(), com.sun.jna.Memory((argv[i].length + 1).toLong()).apply {
                        write(0L, argv[i].toByteArray(), 0, argv[i].length)
                        setByte(argv[i].length.toLong(), 0.toByte())
                    })
                }

                // 4) 调用 node::Start(argc, argv)
                running = true
                notifyServerState(true)
                Log.i(TAG, "[BOOT] 调用 $START_SYMBOL，argv=${argv.toList()}")
                val code = startFn.invokeInt(arrayOf(argv.size, argvMem))
                Log.i(TAG, "node::Start 返回: $code")
                running = false
                notifyServerState(false)
            } catch (e: Throwable) {
                Log.e(TAG, "Node 启动失败", e)
                FileLogger.log("Node 启动失败: ${e.message}")
                running = false
                notifyServerState(false)
            }
        }.apply { name = "node-main" }

        nodeThread!!.start()
    }

    fun isRunning(): Boolean = running

    private fun notifyServerState(ready: Boolean) {
        // 由 NodeService 转发；这里只记录
        Log.i(TAG, "server state -> $ready")
    }

    /** JNA 载入 node 库（惰性） */
    private object NativeLibraryHolder {
        @Volatile
        private var lib: com.sun.jna.NativeLibrary? = null
        fun get(): com.sun.jna.NativeLibrary {
            if (lib == null) {
                synchronized(this) {
                    if (lib == null) {
                        lib = com.sun.jna.NativeLibrary.getInstance("node")
                    }
                }
            }
            return lib!!
        }
    }
}