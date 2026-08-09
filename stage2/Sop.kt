package com.sopcam.sop

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------
 * 数据模型
 *
 * SopTemplate（一种板子的检修流程）
 *   └─ SopStep（一个拍摄点位：U7 主控 / Q3 MOS / J5 排针…）
 * WorkOrder（一次实际检修，绑定一个模板）
 *   └─ CaptureRecord（一张成片）
 * ------------------------------------------------------------------ */

@Entity(tableName = "sop_template")
data class SopTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                       // "EV 逆变器控制板 v2 出厂检验"
    val deviceModel: String = "",           // 板号 / 机型
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sop_step",
    foreignKeys = [ForeignKey(
        entity = SopTemplate::class, parentColumns = ["id"],
        childColumns = ["templateId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("templateId")]
)
data class SopStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val orderIndex: Int,                    // 1,2,3… 拍摄顺序
    val refDes: String = "",                // 位号：U7 / Q3 / J5
    val partName: String = "",              // 元器件：STM32G474 / TJA1043AT
    val detail: String = "",                // 针脚或部位：Pin 12–15 / 焊盘背面
    val hint: String = "",                  // 屏幕提示："对准丝印，保证能看清第 1 脚圆点"
    val requiredShots: Int = 1,
    /** 该步骤默认水印锚点；为空则用全局设置 */
    val anchorOverride: String? = null,
) {
    /** 水印强调行 & 文件名主干，例如 "U7·STM32G474·Pin12-15" */
    fun label(sep: String = "·"): String =
        listOf(refDes, partName, detail).filter { it.isNotBlank() }.joinToString(sep)
}

@Entity(tableName = "work_order")
data class WorkOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,                       // "WO-20260809-017"
    val templateId: Long,
    val serialNo: String = "",              // 被修设备序列号
    val operator: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
)

@Entity(
    tableName = "capture_record",
    indices = [Index("workOrderId"), Index("stepId")]
)
data class CaptureRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workOrderId: Long,
    val stepId: Long?,                      // 自由拍摄时为 null
    val fileName: String,
    val relativePath: String,
    val mediaUri: String,
    @ColumnInfo(defaultValue = "") val voiceNote: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface SopDao {
    @Query("SELECT * FROM sop_template ORDER BY updatedAt DESC")
    fun templates(): Flow<List<SopTemplate>>

    @Query("SELECT * FROM sop_step WHERE templateId = :tid ORDER BY orderIndex")
    fun steps(tid: Long): Flow<List<SopStep>>

    @Query("SELECT * FROM sop_step WHERE templateId = :tid ORDER BY orderIndex")
    suspend fun stepsOnce(tid: Long): List<SopStep>

    @Query("""
        SELECT COUNT(*) FROM capture_record
        WHERE workOrderId = :wid AND stepId = :sid
    """)
    fun shotCount(wid: Long, sid: Long): Flow<Int>

    @Query("SELECT * FROM capture_record WHERE workOrderId = :wid ORDER BY createdAt")
    suspend fun records(wid: Long): List<CaptureRecord>

    @Insert suspend fun insert(t: SopTemplate): Long
    @Insert suspend fun insert(s: SopStep): Long
    @Insert suspend fun insert(w: WorkOrder): Long
    @Insert suspend fun insert(r: CaptureRecord): Long
    @Update suspend fun update(s: SopStep)
    @Query("DELETE FROM sop_step WHERE id = :id") suspend fun deleteStep(id: Long)

    /** 拖拽排序后整体重排 */
    @Transaction
    suspend fun reorder(steps: List<SopStep>) =
        steps.forEachIndexed { i, s -> update(s.copy(orderIndex = i + 1)) }
}

@Database(
    entities = [SopTemplate::class, SopStep::class, WorkOrder::class, CaptureRecord::class],
    version = 1
)
abstract class SopDatabase : RoomDatabase() {
    abstract fun sopDao(): SopDao
}

/* ------------------------------------------------------------------
 * 命名与归档路径
 * ------------------------------------------------------------------ */

object FileNaming {

    private val stamp = SimpleDateFormat("HHmmss", Locale.US)
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** Windows/macOS/Linux 通吃的非法字符集，另外剔掉容易惹事的空格和点 */
    private val illegal = Regex("""[\\/:*?"<>|\r\n\t]""")

    /**
     * 清洗成安全文件名片段。中文保留（NTFS/exFAT/ext4 都支持），
     * 但连续空白折叠成下划线，尾部的点会被去掉（Windows 不允许）。
     */
    fun sanitize(raw: String, maxLen: Int = 40): String {
        var s = raw.replace(illegal, "").trim()
        s = s.replace(Regex("""\s+"""), "_")
        s = s.trimEnd('.', '_')
        if (s.length > maxLen) s = s.take(maxLen).trimEnd('_')
        return s.ifBlank { "未命名" }
    }

    /**
     * 成片文件名。
     *   有 SOP 步骤：  03_U7·STM32G474·Pin12-15_143052
     *   有语音备注：   03_U7·STM32G474_电容鼓包已更换_143052
     *   自由拍摄：     FREE_电源部分虚焊_143052
     *
     * 序号前缀让电脑上按名称排序 = 按 SOP 顺序，不用再排时间。
     */
    fun build(
        step: SopStep?,
        voiceNote: String? = null,
        shotIndex: Int = 1,
        at: Long = System.currentTimeMillis(),
    ): String {
        val head = step?.let {
            val idx = it.orderIndex.toString().padStart(2, '0')
            "${idx}_${sanitize(it.label(), 48)}"
        } ?: "FREE"

        val note = voiceNote?.takeIf { it.isNotBlank() }?.let { "_" + sanitize(it) } ?: ""
        val dup = if (shotIndex > 1) "_$shotIndex" else ""
        return "$head$note$dup" + "_" + stamp.format(Date(at))
    }

    /**
     * 归档目录。电脑端只要把整个 SopCam 文件夹拖过去，层级就是天然分类：
     *   DCIM/SopCam/20260809/WO-20260809-017_SN12345/
     */
    fun relativePath(order: WorkOrder, at: Long = System.currentTimeMillis()): String {
        val folder = listOf(order.code, order.serialNo)
            .filter { it.isNotBlank() }
            .joinToString("_") { sanitize(it, 32) }
        return "DCIM/SopCam/${day.format(Date(at))}/$folder"
    }

    /** 同名冲突兜底（同一步骤补拍时） */
    fun dedupe(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("${base}_$i" in existing) i++
        return "${base}_$i"
    }
}
