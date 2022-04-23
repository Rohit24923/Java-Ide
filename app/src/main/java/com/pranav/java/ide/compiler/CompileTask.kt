package com.pranav.java.ide.compiler

import android.content.Context
import android.os.Looper

import com.google.common.io.Files
import com.pranav.java.ide.MainActivity
import com.pranav.java.ide.R
import com.pranav.lib_android.exception.CompilationFailedException
import com.pranav.lib_android.task.java.CompileJavaTask
import com.pranav.lib_android.task.java.D8Task
import com.pranav.lib_android.task.java.ExecuteJavaTask
import com.pranav.lib_android.task.JavaBuilder
import com.pranav.lib_android.util.FileUtil

import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class CompileTask(context: Context, listeners: CompilerListeners) : Thread() {

    private var d8Time: Long = 0
    private var ecjTime: Long = 0

    private var errorsArePresent = false

    private lateinit var activity: MainActivity

    private lateinit var listener: CompilerListeners

    lateinit var STAGE_CLEAN: String
    lateinit var STAGE_ECJ: String
    lateinit var STAGE_D8TASK: String
    lateinit var STAGE_LOADING_DEX: String
    lateinit var builder: JavaBuilder

    init {
        activity = context as MainActivity
        listener = listeners
        builder = JavaBuilder(context)

        STAGE_CLEAN = context.getString(R.string.stage_clean)
        STAGE_ECJ = context.getString(R.string.stage_ecj)
        STAGE_D8TASK = context.getString(R.string.stage_d8task)
        STAGE_LOADING_DEX = context.getString(R.string.stage_loading_dex)
    }

    override fun run() {
        Looper.prepare()

        try {
            // Delete previous build files
            listener.OnCurrentBuildStageChanged(STAGE_CLEAN)
            FileUtil.deleteFile(FileUtil.getBinDir())
            File(FileUtil.getBinDir()).mkdirs()
            val mainFile = File(FileUtil.getJavaDir(), "Main.java")
            Files.createParentDirs(mainFile)
            // a simple workaround to prevent calls to system.exit
            mainFile.writeText(
                    activity.editor
                            .getText()
                            .toString()
                            .replace("System.exit(", "System.err.print(\"Exit code \" + ")
                    )
        } catch (e: IOException) {
            activity.dialog("Cannot save program", e.message?, true)
            listener.OnFailed()
        }

        // code that runs ecj
        var time: Long = System.currentTimeMillis()
        errorsArePresent = true
        try {
            listener.OnCurrentBuildStageChanged(STAGE_ECJ)
            val javaTask = CompileJavaTask(builder)
            javaTask.doFullTask()
            errorsArePresent = false
        } catch (e: Throwable) {
            activity.showErr(e.message?)
            listener.OnFailed()
        }
        if (errorsArePresent) {
            return
        }

        ecjTime = System.currentTimeMillis() - time
        time = System.currentTimeMillis()

        // run d8
        try {
            listener.OnCurrentBuildStageChanged(STAGE_D8TASK)
            D8Task().doFullTask()
        } catch (e: Throwable) {
            errorsArePresent = true
            activity.showErr(e.message?)
            listener.OnFailed()
            return
        }
        d8Time = System.currentTimeMillis() - time
        // code that loads the final dex
        try {
            listener.OnCurrentBuildStageChanged(STAGE_LOADING_DEX)
            val classes = activity.getClassesFromDex()
            if (classes.equals(null)) {
                return
            }
            listener.OnSuccess()
            activity.listDialog(
                    "Select a class to execute",
                    classes,
                    { _, item ->
                        val task = ExecuteJavaTask(builder, classes[item])
                        try {
                            task.doFullTask()
                        } catch (e: InvocationTargetException) {
                            activity.dialog(
                                    "Failed...",
                                    "Runtime error: " + e.message + "\n\n" + e.message,
                                    true)
                        } catch (e: Exception) {
                            activity.dialog(
                                    "Failed..",
                                    "Couldn't execute the dex: "
                                            + e.toString()
                                            + "\n\nSystem logs:\n"
                                            + task.getLogs(),
                                    true)
                        }
                        val s = StringBuilder()
                        s.append("Success! ECJ took: ")
                        s.append(ecjTime.toString())
                        s.append("ms, ")
                        s.append("D8")
                        s.append(" took: ")
                        s.append(d8Time.toString())
                        s.append("ms")

                        activity.dialog(s.toString(), task.getLogs(), true)
                    })
        } catch (e: Throwable) {
            listener.OnFailed()
            activity.showErr(e.message?)
        }
    }

    interface CompilerListeners {
        fun OnCurrentBuildStageChanged(stage: String)

        fun OnSuccess()

        fun OnFailed()
    }
}
