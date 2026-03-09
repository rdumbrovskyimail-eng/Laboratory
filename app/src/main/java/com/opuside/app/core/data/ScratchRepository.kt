package com.opuside.app.feature.scratch.data

import com.opuside.app.feature.scratch.data.local.ScratchDao
import com.opuside.app.feature.scratch.data.local.ScratchEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScratchRepository @Inject constructor(
    private val dao: ScratchDao
) {
    fun getAllRecords(): Flow<List<ScratchEntity>> = dao.getAllRecords()

    suspend fun save(content: String): Long {
        val number = dao.count() + 1
        val entity = ScratchEntity(
            title = "Запись $number",
            content = content
        )
        return dao.insert(entity)
    }

    suspend fun delete(record: ScratchEntity) = dao.delete(record)
}
