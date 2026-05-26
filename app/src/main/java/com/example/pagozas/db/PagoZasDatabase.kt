package com.example.pagozas.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Pago::class], version = 1, exportSchema = false)
abstract class PagoZasDatabase : RoomDatabase() {

    abstract fun pagoDao(): PagoDao

    companion object {
        @Volatile
        private var INSTANCE: PagoZasDatabase? = null

        fun getDatabase(context: Context): PagoZasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PagoZasDatabase::class.java,
                    "pagozas_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
