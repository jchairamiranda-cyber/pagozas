package com.example.pagozas.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PagoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pago: Pago)

    @Query("SELECT * FROM pagos ORDER BY timestamp DESC")
    fun getAllPagos(): Flow<List<Pago>>
}
