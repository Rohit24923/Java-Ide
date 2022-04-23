package com.pranav.lib_android.code.disassembler

import org.eclipse.jdt.internal.core.util.Disassembler

import java.io.File

class ClassFileDisassembler(classFile: String) {

    private var classFileBytes: ByteArray

    init {
        classFileBytes = File(classFile).readBytes()
    }

    fun disassemble() = Disassembler().disassemble(classFileBytes, System.lineSeparator())
}
