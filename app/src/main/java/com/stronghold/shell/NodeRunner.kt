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
        // 从设置读取环境变量（PORT / SP_COMBAT / SP_PLUGIN_SCRIPTS 等）
        val env = try {
            Settings.toEnv(Settings.load(context))
        } catch (_: Exception) { emptyMap() }
        startWithEnv(context, bundleDir, entryName, logFile, env)
    }

    /**
     * 带环境变量启动。
     *
     * 【原理】Android 的 setenv 只在 C 层生效，Node 启动时会读 environ。
     * 用 JNA 调 libc 的 setenv() 写入，再调用 node::Start。
     */
    fun startWithEnv(
        context: Context,
        bundleDir: String,
        entryName: String,
        logFile: File?,
        env: Map<String, String>
    ) {
        if (running) {
            Log.w(TAG, "Node 已在运行，忽略重复启动")
            return
        }

        nodeThread = Thread {
            try {
                // 0) 切换进程工作目录到 bundle 根（Node 读取 package.json/资源的相对路径依赖 cwd）
                val dir = File(bundleDir)
                if (dir.isDirectory) {
                    try {
                        com.sun.jna.NativeLibrary.getInstance("c")
                            .getFunction("chdir")
                            .invokeInt(arrayOf(bundleDir))
                        Log.i(TAG, "chdir -> $bundleDir")
                    } catch (e: Throwable) {
                        Log.w(TAG, "chdir 失败: ${e.message}")
                    }
                }

                // 0.5) 注入环境变量（必须在 loaded node 之前/之后都生效，setenv 全局）
                try {
                    val libc = com.sun.jna.NativeLibrary.getInstance("c")
                    val setenv = libc.getFunction("setenv")
                    for ((k, v) in env) {
                        setenv.invokeInt(arrayOf(k, v, 1))
                        Log.i(TAG, "env: $k=$v")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "setenv 失败: ${e.message}")
                }

                // 1) 载入 libnode.so
                System.loadLibrary("node")
                Log.i(TAG, "libnode.so 载入成功")

                // 2) 拿到 node::Start 函数指针
                val nl = NativeLibraryHolder.get()
                val startFn = nl.getFunction(START_SYMBOL)
                Log.i(TAG, "已获取 $START_SYMBOL")

                // 3) 构造 argv：Node 约定 argv[0]=可执行名, argv[1]=脚本路径, 之后是传给脚本的参数
                //    脚本用绝对路径最稳；上游 server/index.js 会自行读取同目录资源。
                val scriptPath = File(dir, entryName).absolutePath
                val argv = arrayOf(
                    "node",      // argv[0]
                    scriptPath   // argv[1]
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