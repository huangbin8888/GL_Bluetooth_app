package com.guanglu.caliper.core.usecase

import com.guanglu.caliper.core.database.dao.BatchDao
import com.guanglu.caliper.core.database.entity.Batch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 默认批次的创建与查询。
 *
 * @author AI-Generated
 */
class BatchUseCase(
    private val dao: BatchDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    fun ensureDefault(batchId: Long) {
        scope.launch {
            if (dao.find(batchId) == null) {
                dao.insert(Batch(name = "默认批次", createdAt = System.currentTimeMillis()))
            }
        }
    }
}
