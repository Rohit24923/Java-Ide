package com.pranav.java.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.common.base.Charsets
import com.google.common.io.ByteStreams
import com.google.common.io.Files
import com.googlecode.d2j.smali.BaksmaliCmd
import com.pranav.java.ide.compiler.CompileTask
import com.pranav.lib_android.code.disassembler.ClassFileDisassembler
import com.pranav.lib_android.code.formatter.Formatter
import com.pranav.lib_android.task.JavaBuilder
import com.pranav.lib_android.util.ConcurrentUtil
import com.pranav.lib_android.util.FileUtil
import com.pranav.lib_android.util.ZipUtil

import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile

import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.Arrays

class MainActivity : AppCompatActivity() {

    lateinit var editor: CodeEditor
    lateinit var mClassLoader: ClassLoader

    private lateinit var loadingDialog: AlertDialog
    private lateinit var runThread: Thread

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mClassLoader = getClassLoader()
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        getSupportActionBar()?.setDisplayHomeAsUpEnabled(false)
        getSupportActionBar()?.setHomeButtonEnabled(false)

        editor = findViewById(R.id.editor)

        editor.setTypefaceText(Typeface.MONOSPACE)
        editor.setEditorLanguage(JavaLanguage())
        editor.setColorScheme(SchemeDarcula())
        editor.setTextSize(12f)

        val file = File(FileUtil.getJavaDir(), "Main.java")

        if (file.exists()) {
            try {
                editor.setText(file.readText())
            } catch (e: Exception) {
                dialog("Cannot read file", getString(e), true)
            }
        } else {
            editor.setText(
                    "package com.example;\n\nimport java.util.*;\n\n"
                            + "public class Main {\n\n"
                            + "\tpublic static void main(String[] args) {\n"
                            + "\t\tSystem.out.print(\"Hello, World!\");\n"
                            + "\t}\n"
                            + "}\n")
        }

        ConcurrentUtil.executeInBackground {
                    if (!File(FileUtil.getClasspathDir(), "android.jar").exists()) {
                        ZipUtil.unzipFromAssets(
                                this@MainActivity, "android.jar.zip", FileUtil.getClasspathDir())
                    }
                    val stubs = File(FileUtil.getClasspathDir(), "/core-lambda-stubs.jar")
                    if (!stubs.exists()
                            && getSharedPreferences("compiler_settings", Context.MODE_PRIVATE)
                                    .getString("javaVersion", "7.0")
                                    .equals("8.0")) {
                        try {
                            stubs.writeBytes(getAssets().open("core-lambda-stubs.jar").readBytes())
                        } catch (e: Exception) {
                            showErr(getString(e))
                        }
                    }
                }
        /* Create Loading Dialog */
        buildLoadingDialog()

        (findViewById(R.id.btn_disassemble) as View).setOnClickListener { _ -> disassemble()}
        (findViewById(R.id.btn_smali2java) as View).setOnClickListener { _ -> decompile()}
        (findViewById(R.id.btn_smali) as View).setOnClickListener { _ -> smali()}
    }

    /* Build Loading Dialog - This dialog shows on code compilation */
    fun buildLoadingDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        val viewGroup: ViewGroup = findViewById(android.R.id.content)
        val dialogView: View =
                getLayoutInflater().inflate(R.layout.compile_loading_dialog, viewGroup, false)
        builder.setView(dialogView)
        loadingDialog = builder.create()
        loadingDialog.setCancelable(false)
        loadingDialog.setCanceledOnTouchOutside(false)
    }

    /* To Change visible to user Stage TextView Text to actually compiling stage in Compile.java */
    fun changeLoadingDialogBuildStage(stage: String) {
        if (loadingDialog.isShowing()) {
            /* So, this method is also triggered from another thread (Compile.java)
             * We need to make sure that this code is executed on main thread */
            runOnUiThread {
                    val stage_txt: TextView? = loadingDialog.findViewById(R.id.stage_txt)
                    stage_txt?.setText(stage)
                }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        getMenuInflater().inflate(R.menu.main_activity_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.getItemId()) {
            R.id.format_menu_button -> {
                val formatter = Formatter(editor.getText().toString())
                ConcurrentUtil.execute {
                    editor.setText(formatter.format())
                }
            }

            R.id.settings_menu_button -> {
                val intent = Intent(this, SettingActivity::class.java)
                startActivity(intent)
            }

            R.id.run_menu_button -> {
                loadingDialog.show() // Show Loading Dialog
                runThread =
                        Thread(
                                CompileTask(
                                        this@MainActivity,
                                        CompileTask.CompilerListeners() {
                                            override fun OnCurrentBuildStageChanged(stage: String) {
                                                changeLoadingDialogBuildStage(stage)
                                            }

                                            override fun OnSuccess() {
                                                loadingDialog.dismiss()
                                            }

                                            override fun OnFailed() {
                                                if (loadingDialog.isShowing()) {
                                                    loadingDialog.dismiss()
                                                }
                                            }
                                        }))
                runThread.start()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    fun showErr(e: String?) {
        Snackbar.make(
                        findViewById(R.id.container) as LinearLayout,
                        "An error occurred",
                        Snackbar.LENGTH_INDEFINITE)
                .setAction("Show error", { _ -> dialog("Failed...", e, true)})
                .show()
    }

    fun smali() {
        try {
            val classes = getClassesFromDex()
            if (classes == null) return
            listDialog(
                    "Select a class to extract source",
                    classes,
                    { _, pos ->
                        val claz = classes[pos]
                        val args =
                                arrayOf(
                                    "-f",
                                    "-o",
                                    FileUtil.getBinDir().plus("smali/"),
                                    FileUtil.getBinDir().plus("classes.dex")
                                )
                        ConcurrentUtil.execute { BaksmaliCmd.main(args) }

                        val edi = CodeEditor(this)
                        edi.setTypefaceText(Typeface.MONOSPACE)
                        edi.setEditorLanguage(JavaLanguage())
                        edi.setColorScheme(SchemeDarcula())
                        edi.setTextSize(13f)

                        val smaliFile =
                                File(
                                        FileUtil.getBinDir()
                                                + "smali/"
                                                + claz.replace(".", "/")
                                                + ".smali")

                        try {
                            edi.setText(
                                    formatSmali(smaliFile.readText()))
                        } catch (e: IOException) {
                            dialog("Cannot read file", getString(e), true)
                        }

                        val dialog =
                                AlertDialog.Builder(this).setView(edi).create()
                        dialog.setCanceledOnTouchOutside(true)
                        dialog.show()
                    })
        } catch (e: Throwable) {
            dialog("Failed to extract smali source", getString(e), true)
        }
    }

    fun decompile() {
        val classes = getClassesFromDex()
        if (classes == null) return
        listDialog(
                "Select a class to extract source",
                classes,
                { _, pos ->
                    val claz = classes[pos].replace(".", "/")
                    val args = arrayOf(
                        FileUtil.getBinDir()
                                + "classes/"
                                + claz
                                + // full class name
                                ".class",
                        "--extraclasspath",
                        FileUtil.getClasspathDir() + "android.jar",
                        "--outputdir",
                        FileUtil.getBinDir() + "cfr/"
                    )

                    ConcurrentUtil.execute {
                                try {
                                    org.benf.cfr.reader.Main.main(args)
                                } catch (e: Exception) {
                                    dialog("Failed to decompile...", getString(e), true)
                                }
                            }

                    val edi = CodeEditor(this)
                    edi.setTypefaceText(Typeface.MONOSPACE)
                    edi.setEditorLanguage(JavaLanguage())
                    edi.setColorScheme(SchemeDarcula())
                    edi.setTextSize(12f)

                    val decompiledFile = File(FileUtil.getBinDir(), "cfr/" + claz + ".java")

                    try {
                        edi.setText(decompiledFile.readText())
                    } catch (e: IOException) {
                        dialog("Cannot read file", getString(e), true)
                    }

                    val d =
                            AlertDialog.Builder(this).setView(edi).create()
                    d.setCanceledOnTouchOutside(true)
                    d.show()
                })
    }

    fun disassemble() {
        val classes = getClassesFromDex()
        if (classes == null) return
        listDialog(
                "Select a class to disassemble",
                classes,
                { _, pos ->
                    val claz = classes[pos].replace(".", "/")

                    val edi = CodeEditor(this)
                    edi.setTypefaceText(Typeface.MONOSPACE)
                    edi.setEditorLanguage(JavaLanguage())
                    edi.setColorScheme(SchemeDarcula())
                    edi.setTextSize(12f)

                    try {
                        val disassembled =
                                ClassFileDisassembler(
                                                FileUtil.getBinDir() + "classes/" + claz + ".class")
                                        .disassemble()

                        edi.setText(disassembled)

                        val d =
                                AlertDialog.Builder(this).setView(edi).create()
                        d.setCanceledOnTouchOutside(true)
                        d.show()
                    } catch (e: Throwable) {
                        dialog("Failed to disassemble", getString(e), true)
                    }
                })
    }

    private fun formatSmali(inp: String): String {

        val lines = Arrays.asList(inp.split("\n"))

        var insideMethod = false

        for (i in 0 until lines.size) {
            val line = lines.get(i)

            if (line.startsWith(".method")) insideMethod = true

            if (line.startsWith(".end method")) insideMethod = false

            if (insideMethod and !shouldSkip(line)) {
                lines.set(i, line + "\n")
            }
        }

        val result = StringBuilder()

        for (i in 0 until lines.size) {
            if (i != 0) result.append("\n")

            result.append(lines.get(i))
        }

        return result.toString()
    }

    private fun shouldSkip(smaliLine: String): Boolean {

        val ops = arrayOf(".line", ":", ".prologue")

        for (op in ops) {
            if (smaliLine.trim().startsWith(op)) return true
        }
        return false
    }

    fun listDialog(title: String, items: Array<String>, listener: DialogInterface.OnClickListener) {
        runOnUiThread {
                    /*
                     * @TheWolf:
                     * This method is executed on another
                     * Thread, so DialogBuilder must be (I didn't find other solutions)
                     * in runOnUiThread
                     */

                    MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(title)
                            .setItems(items, listener)
                            .create()
                            .show()
                }
    }

    fun dialog(title: String, message: String?, copyButton: Boolean) {
        val dialog =
                MaterialAlertDialogBuilder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("GOT IT", null)
                        .setNegativeButton("CANCEL", null)
        if (copyButton)
            dialog.setNeutralButton(
                    "COPY",
                    { _, _ ->
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("clipboard", message))
                    })
        dialog.create().show()
    }

    fun getClassesFromDex(): Array<String>? {
        try {
            val classes = ArrayList<String>()
            val dexfile =
                    DexFileFactory.loadDexFile(
                            FileUtil.getBinDir().plus("classes.dex"), Opcodes.forApi(26))
            for (classDef in dexfile.getClasses().toTypedArray()) {
                val name = classDef.getType().replace("/", ".") // convert class name to standard form
                classes.add(name.substring(1, name.length - 1))
            }
            return classes.toTypedArray()
        } catch (e: Exception) {
            dialog("Failed to get available classes in dex...", getString(e), true)
            return null
        }
    }


    private fun getString(e: Throwable): String {
        return Log.getStackTraceString(e)
    }
}
