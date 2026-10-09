package dev.whispr.data.messaging

import dev.whispr.data.db.SettingDao
import dev.whispr.data.db.SettingEntity
import dev.whispr.domain.model.PrivacySettings
import dev.whispr.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Privacy toggles, stored in the encrypted database. Typing defaults to off; receipts and screen security to on. */
class RoomSettingsRepository(private val dao: SettingDao) : SettingsRepository {
    override fun observePrivacy(): Flow<PrivacySettings> = dao.observeAll().map { rows ->
        val map = rows.associate { it.key to it.value }
        PrivacySettings(
            readReceipts = map[READ_RECEIPTS] != "false",
            typingIndicators = map[TYPING] == "true",
            screenSecurity = map[SCREEN_SECURITY] != "false",
            relayCalls = map[RELAY_CALLS] == "true",
            updateChecks = map[UPDATE_CHECKS] != "false",
            saveFolder = map[SAVE_FOLDER]?.ifBlank { null },
        )
    }

    override suspend fun setReadReceipts(enabled: Boolean) = dao.put(SettingEntity(READ_RECEIPTS, enabled.toString()))

    override suspend fun setTypingIndicators(enabled: Boolean) = dao.put(SettingEntity(TYPING, enabled.toString()))

    override suspend fun setScreenSecurity(enabled: Boolean) =
        dao.put(SettingEntity(SCREEN_SECURITY, enabled.toString()))

    override suspend fun setRelayCalls(enabled: Boolean) = dao.put(SettingEntity(RELAY_CALLS, enabled.toString()))

    override suspend fun setUpdateChecks(enabled: Boolean) = dao.put(SettingEntity(UPDATE_CHECKS, enabled.toString()))

    override suspend fun setSaveFolder(uri: String?) = dao.put(SettingEntity(SAVE_FOLDER, uri.orEmpty()))

    companion object {
        private const val UPDATE_CHECKS = "app.update_checks"
        private const val SAVE_FOLDER = "files.save_folder"
        private const val RELAY_CALLS = "privacy.relay_calls"
        internal const val READ_RECEIPTS = "privacy.read_receipts"
        private const val TYPING = "privacy.typing_indicators"
        private const val SCREEN_SECURITY = "privacy.screen_security"
    }
}
