package com.dzshzx.zeta.phone.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking

/**
 * 经健康连接的公开接口（androidx.health.connect）读一段时间的健康数据，交给 [HealthSummary] 排版。 权限是普通的运行时权限，由权限健康页经 root `pm
 * grant` 授予；读到的数据只在内存里，不写日志、不留存。
 */
internal object HealthReader {
    /** 每类最多取这么多条记录，够算一年内的汇总。 */
    private const val PAGE = 1_000

    fun available(context: Context): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
        .getOrDefault(false)

    /** 已授予的健康权限（只看 [HealthSummary.PERMISSIONS] 里的），本地查询，不经 root。 */
    fun granted(context: Context): Set<String> =
        HealthSummary.PERMISSIONS.filter {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            .toSet()

    /** 读 [start, end) 的数据。在后台线程调用。 */
    fun read(context: Context, start: Long, end: Long, zone: ZoneId): HealthData = runBlocking {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val denied = HealthKind.entries.filter { it.permission !in granted }.toSet()
        val failed = LinkedHashMap<HealthKind, String>()
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))

        suspend fun <T> attempt(kind: HealthKind, block: suspend () -> T): T? {
            if (kind in denied) return null
            return try {
                block()
            } catch (e: SecurityException) {
                failed[kind] = reason(e, HealthSummary.BACKGROUND in granted)
                null
            } catch (e: Exception) {
                failed[kind] = e.javaClass.simpleName + ": " + e.message.orEmpty()
                null
            }
        }

        suspend fun <T : Record> records(type: KClass<T>): List<T> =
            client
                .readRecords(
                    ReadRecordsRequest(type, range, ascendingOrder = false, pageSize = PAGE),
                )
                .records

        val stepsTotal =
            attempt(HealthKind.Steps) {
                client
                    .aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[
                        StepsRecord.COUNT_TOTAL] ?: 0L
            }
        val days = (end - start) / PersonalQueries.DAY
        val stepsByDay =
            if (days > HealthSummary.DAILY_MAX_DAYS) null
            else
                attempt(HealthKind.Steps) {
                    val from = Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime()
                    val to = Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime()
                    client
                        .aggregateGroupByPeriod(
                            AggregateGroupByPeriodRequest(
                                setOf(StepsRecord.COUNT_TOTAL),
                                TimeRangeFilter.between(from, to),
                                Period.ofDays(1),
                            ),
                        )
                        .map {
                            it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L)
                        }
                }
        val sleep =
            attempt(HealthKind.Sleep) {
                records(SleepSessionRecord::class).map {
                    HealthSession(it.startTime.toEpochMilli(), it.endTime.toEpochMilli())
                }
            }
        val exercise =
            attempt(HealthKind.Exercise) {
                records(ExerciseSessionRecord::class).map {
                    HealthSession(
                        it.startTime.toEpochMilli(),
                        it.endTime.toEpochMilli(),
                        it.exerciseType,
                        it.title,
                    )
                }
            }
        val heart =
            attempt(HealthKind.HeartRate) {
                client
                    .aggregate(
                        AggregateRequest(
                            setOf(
                                HeartRateRecord.BPM_AVG,
                                HeartRateRecord.BPM_MIN,
                                HeartRateRecord.BPM_MAX,
                                HeartRateRecord.MEASUREMENTS_COUNT,
                            ),
                            range,
                        ),
                    )
                    .let {
                        stats(
                            it,
                            HeartRateRecord.BPM_AVG,
                            HeartRateRecord.BPM_MIN,
                            HeartRateRecord.BPM_MAX,
                            it[HeartRateRecord.MEASUREMENTS_COUNT],
                        )
                    }
            }
        val resting =
            attempt(HealthKind.RestingHeartRate) {
                client
                    .aggregate(
                        AggregateRequest(
                            setOf(
                                RestingHeartRateRecord.BPM_AVG,
                                RestingHeartRateRecord.BPM_MIN,
                                RestingHeartRateRecord.BPM_MAX,
                            ),
                            range,
                        ),
                    )
                    .let {
                        stats(
                            it,
                            RestingHeartRateRecord.BPM_AVG,
                            RestingHeartRateRecord.BPM_MIN,
                            RestingHeartRateRecord.BPM_MAX,
                            null,
                        )
                    }
            }
        val weight =
            attempt(HealthKind.Weight) {
                records(WeightRecord::class).map {
                    HealthReading(it.time.toEpochMilli(), it.weight.inKilograms)
                }
            }
        val oxygen =
            attempt(HealthKind.Oxygen) {
                records(OxygenSaturationRecord::class).map {
                    HealthReading(it.time.toEpochMilli(), it.percentage.value)
                }
            }
        HealthData(
            stepsTotal = stepsTotal,
            stepsByDay = stepsByDay.orEmpty(),
            sleep = sleep.orEmpty(),
            exercise = exercise.orEmpty(),
            heart = heart,
            restingHeart = resting,
            weight = weight.orEmpty(),
            oxygen = oxygen.orEmpty(),
            denied = denied,
            failed = failed,
        )
    }

    private fun stats(
        result: AggregationResult,
        avg: androidx.health.connect.client.aggregate.AggregateMetric<Long>,
        min: androidx.health.connect.client.aggregate.AggregateMetric<Long>,
        max: androidx.health.connect.client.aggregate.AggregateMetric<Long>,
        count: Long?,
    ): HeartStats? {
        val a = result[avg] ?: return null
        return HeartStats(a, result[min] ?: a, result[max] ?: a, count)
    }

    /** 健康连接拒绝读取时的说明：多半是 Zeta 在后台、没有后台读取权限。 */
    private fun reason(e: SecurityException, background: Boolean): String =
        if (!background) "健康连接只让前台应用读取，Zeta 没有后台读取权限。" + HealthSummary.GRANT_HINT
        else "健康连接拒绝读取：" + e.message.orEmpty()
}
