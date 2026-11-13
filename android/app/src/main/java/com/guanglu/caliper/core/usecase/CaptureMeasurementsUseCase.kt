package com.guanglu.caliper.core.usecase

import android.os.SystemClock
import com.guanglu.caliper.core.database.dao.MeasurementDao
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.datastore.SettingsRepository
import com.guanglu.caliper.core.model.CaliperFrame
import com.guanglu.caliper.core.model.MeasurementDraft
import com.guanglu.caliper.core.model.Source
import com.guanglu.caliper.core.model.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 收帧、换算、去重与批量落库。
 *
 * @author AI-Generated
 */
class CaptureMeasurementsUseCase(
    private val dao: MeasurementDao,
    private val settings: SettingsRepository,
    private val clocks: Clocks = Clocks(),
    private val onStored: (Int) -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {}
) {
    class Clocks(
        val wall: () -> Long = { System.currentTimeMillis() },
        val elapsed: () -> Long = { SystemClock.elapsedRealtime() }
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debouncer = Debouncer(settings.debounceDeltaUm, settings.debounceWindowMs)
    private val buffer = mutableListOf<Measurement>()
    private val lock = Any()
    private val seq = AtomicInteger(0)
    private val mFlushSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in mFlushSignal) {
                flush()
            }
        }
    }

    fun onFrame(
        channelNo: Int,
        mac: String,
        frame: CaliperFrame.Measurement,
        source: Source? = null
    ) {
        val elapsed = clocks.elapsed()
        if (!debouncer.shouldAccept(channelNo, frame.valueUm, elapsed)) {
            onDiagnostic("DEDUP CH$channelNo ${frame.rawValue}")
            return
        }
        val verdict = Units.judge(frame.valueUm, settings.lowerLimitUm, settings.upperLimitUm)
        val resolvedSource = source ?: (if (frame.needsAck) Source.DAT0 else Source.DAT1)
        val draft = MeasurementDraft(
            channelNo = channelNo,
            deviceMac = mac,
            valueUm = frame.valueUm,
            rawValue = frame.rawValue,
            rawUnit = frame.unit,
            sign = frame.sign,
            source = resolvedSource,
            capturedAt = clocks.wall(),
            elapsedRealtime = elapsed,
            verdict = verdict
        )
        val entity = toEntity(draft, settings.activeBatchId, seq.incrementAndGet())
        synchronized(lock) { buffer.add(entity) }
        mFlushSignal.trySend(Unit)
    }

    /** HID 键盘录入帧走同一条落库链路。 */
    fun onHidFrame(channelNo: Int, mac: String, frame: CaliperFrame.Measurement) =
        onFrame(channelNo, mac, frame, Source.HID)

    suspend fun flush() {
        val batch = synchronized(lock) {
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        if (batch.isEmpty()) return
        val ids = dao.insertAll(batch)
        withContext(Dispatchers.Main) { onStored(ids.size) }
    }

    fun resetDebounce() = debouncer.clearAll()

    fun close() {
        mFlushSignal.close()
        scope.cancel()
    }

    companion object {
        fun toEntity(draft: MeasurementDraft, batchId: Long, seq: Int): Measurement = Measurement(
            batchId = batchId,
            seq = seq,
            channelNo = draft.channelNo,
            deviceMac = draft.deviceMac,
            valueUm = draft.valueUm,
            rawValue = draft.rawValue,
            rawUnit = draft.rawUnit.toString(),
            sign = draft.sign.toString(),
            capturedAt = draft.capturedAt,
            elapsedRealtime = draft.elapsedRealtime,
            verdict = draft.verdict.name,
            note = draft.note,
            source = draft.source.name,
            legacyChannel = draft.legacyChannel
        )
    }
}
