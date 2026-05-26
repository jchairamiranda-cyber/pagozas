package com.example.pagozas.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pagos")
data class Pago(
    @PrimaryKey val codigo: String,
    val monto: String,
    val fecha: String,
    val timestamp: Long = System.currentTimeMillis()
)
