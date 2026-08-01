package com.example.mtsignin.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val passwordEncrypted: String,  // 加密后的密码
    val nickname: String? = null,
    val lastSignInTime: Long? = null,
    val lastSignInStatus: String? = null,
    val lastSignInRanking: String? = null,
    val lastSignInReward: String? = null,
    val lastToken: String? = null,
    val isEnabled: Boolean = true,
    val createTime: Long = System.currentTimeMillis()
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createTime DESC")
    fun getAll(): Flow<List<AccountEntity>>
    
    @Query("SELECT * FROM accounts WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<AccountEntity>
    
    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long
    
    @Update
    suspend fun update(account: AccountEntity)
    
    @Delete
    suspend fun delete(account: AccountEntity)
    
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}

