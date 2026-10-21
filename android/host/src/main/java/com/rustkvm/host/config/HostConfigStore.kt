package com.rustkvm.host.config

import android.util.Log
import com.rustkvm.protocol.KvmConfig
import com.rustkvm.protocol.encodeConfig
import com.rustkvm.protocol.parseConfig
import java.io.File

/** 与 `src/config.rs` 的 `ConfigStore` 同一份 JSON、同样的取舍。 */
class HostConfigStore(private val file: File) {
    @Volatile
    private var current: KvmConfig = load()

    fun get(): KvmConfig = current

    /** 先落盘再更新内存：写失败时内存里还是旧值，不会出现「显示保存成功、重启后变回去」。 */
    @Synchronized
    fun save(config: KvmConfig) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(encodeConfig(config))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "写配置 ${file.path} 失败" }
        }
        current = config
    }

    // 读不到或读坏了都退回默认值：配置坏了不该让服务起不来，那样连设置页都打不开
    private fun load(): KvmConfig {
        if (!file.exists()) return KvmConfig.DEFAULT
        return runCatching { parseConfig(file.readText()) }
            .onFailure { Log.w(TAG, "配置 ${file.path} 解析失败，改用默认值", it) }
            .getOrDefault(KvmConfig.DEFAULT)
    }

    private companion object {
        const val TAG = "HostConfigStore"
    }
}
