package com.pranav.java.ide

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingActivity : AppCompatActivity() {
    private val javaVersions = arrayOf<String>(
        "1.3", "1.4", "5.0", "6.0", "7.0", "8.0", "9.0", "10.0", "11.0", "12.0", "13.0", "14.0",
        "15.0", "16.0", "17.0"
    )

    private var javaVersions_spinner: Spinner
    private var classpath_bttn: MatetialButton

    private var alertDialog: AlertDialog
    private var settings: SharedPreferences

    private val TAG = "SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        val toolbar: Toolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)
        getSupportActionBar()?.setDisplayHomeAsUpEnabled(true)
        getSupportActionBar()?.setHomeButtonEnabled(true)
        toolbar.setNavigationOnClickListener({ _ ->
            onBackPressed()
        })

        settings = getSharedPreferences("compiler_settings", MODE_PRIVATE)

        javaVersions_spinner = findViewById(R.id.javaVersion_spinner)
        classpath_bttn = findViewById(R.id.classpath_bttn)

        val adapter =
                ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, javaVersions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        javaVersions_spinner.setAdapter(adapter)

        /* Check if Classpath stored in SharedPref is empty - if yes, change button text */
        if (settings.getString("classpath", "").equals("")) {
            classpath_bttn.setText(getString(R.string.classpath_not_specified))
        } else {
            classpath_bttn.setText(getString(R.string.classpath_edit))
        }

        /* Select Version in Spinner based on SharedPreferences Value */
        var count = 0
        for (version : javaVersions) {
            if (version.equals(settings.getString("javaVersion", "7.0"))) {
                javaVersions_spinner.setSelection(count)
                break
            }
            count++
        }

        /* Save Selected Java Version in SharedPreferences */
        javaVersions_spinner.setOnItemSelectedListener(
                AdapterView.OnItemSelectedListener() {
                    override fun onItemSelected(
                            adapterView: AdapterView<?>, view: View, i: Int, l: Long) {
                        settings.edit()
                                .putString("javaVersion", javaVersions[i])
                                .apply()
                        Log.e(TAG, "Selected Java Version (By User): " + javaVersions[i])
                    }

                    override fun onNothingSelected(adapterView: AdapterView<?>) {}
                });

        buildClasspathDialog()

        classpath_bttn.setOnClickListener(
                { _ ->
                    alertDialog.show()

                    val classpath_edt: TextInputEditText = alertDialog.findViewById(R.id.classpath_edt)
                    val save_classpath_bttn: MaterialButton =
                            alertDialog.findViewById(R.id.save_classpath_bttn)

                    if (!settings.getString("classpath", "").equals("")) {
                        classpath_edt.setText(settings.getString("classpath", ""))
                    }

                    save_classpath_bttn.setOnClickListener(
                            { _ ->
                                val enteredClasspath = classpath_edt.getText().toString()
                                settings.edit().putString("classpath", enteredClasspath).apply()

                                /* Check if specified classpath is empty - if yes, change button text */
                                if (enteredClasspath.equals("")) {
                                    classpath_bttn.setText(
                                            getString(R.string.classpath_not_specified))
                                } else {
                                    classpath_bttn.setText(getString(R.string.classpath_edit))
                                }

                                /* Dismiss Dialog If Showing */
                                if (alertDialog.isShowing()) alertDialog.dismiss()
                            })
                })
    }

    fun buildClasspathDialog() {
        val builder = AlertDialog.Builder(this)
        val viewGroup: ViewGroup = findViewById(android.R.id.content)
        val dialogView: View = getLayoutInflater().inflate(R.layout.classpath_dialog, viewGroup, false)
        builder.setView(dialogView)
        alertDialog = builder.create()
    }

    override fun onDestroy() {
        if (alertDialog.isShowing()) {
            alertDialog.dismiss()
        }
        super.onDestroy()
    }
}
