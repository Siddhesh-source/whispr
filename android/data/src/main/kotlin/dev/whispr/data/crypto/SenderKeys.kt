package dev.whispr.data.crypto

import dev.whispr.data.db.GroupDao
import dev.whispr.data.db.SenderKeyEntity
import java.util.UUID
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore

/**
 * libsignal's sender-key store on the SQLCipher database. Like [SignalStore]
 * it is called synchronously by libsignal, so only use it on the crypto
 * thread inside a transaction.
 */
class SenderKeys(private val dao: GroupDao) : SenderKeyStore {
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        dao.putSenderKey(SenderKeyEntity(sender.name, distributionId.toString(), record.serialize()))

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        dao.senderKey(sender.name, distributionId.toString())?.let(::SenderKeyRecord)
}
