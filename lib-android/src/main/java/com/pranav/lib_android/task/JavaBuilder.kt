package com.pranav.lib_android.task

import android.content.Context
import com.pranav.lib_android.interfaces.Builder

class JavaBuilder constructor(
    val mContext: Context
  ): Builder() {
  
  override fun getContext(): Context {
    return mContext
  }
}
