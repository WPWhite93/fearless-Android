package jp.co.soramitsu.runtime.multiNetwork.runtime

import android.util.Log
import jp.co.soramitsu.common.data.network.runtime.binding.bindNumber
import jp.co.soramitsu.common.data.network.runtime.binding.requireType
import jp.co.soramitsu.common.utils.constant
import jp.co.soramitsu.common.utils.system
import jp.co.soramitsu.core.runtime.ChainConnection
import jp.co.soramitsu.coredb.dao.ChainDao
import jp.co.soramitsu.runtime.multiNetwork.ChainState
import jp.co.soramitsu.runtime.multiNetwork.ChainsStateTracker
import jp.co.soramitsu.shared_utils.runtime.RuntimeSnapshot
import jp.co.soramitsu.shared_utils.runtime.definitions.types.composite.Struct
import jp.co.soramitsu.shared_utils.runtime.definitions.types.fromByteArrayOrNull
import jp.co.soramitsu.shared_utils.wsrpc.executeAsync
import jp.co.soramitsu.shared_utils.wsrpc.mappers.nonNull
import jp.co.soramitsu.shared_utils.wsrpc.mappers.pojo
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.chain.RuntimeVersion
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.chain.RuntimeVersionRequest
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.chain.StateRuntimeVersionRequest
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.chain.SubscribeRuntimeVersionRequest
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.chain.SubscribeStateRuntimeVersionRequest
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.chain.runtimeVersionChange
import jp.co.soramitsu.shared_utils.wsrpc.state.SocketStateMachine
import jp.co.soramitsu.shared_utils.wsrpc.subscriptionFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class RuntimeVersionSubscription(
    private val chainId: String,
    connection: ChainConnection,
    private val chainDao: ChainDao,
    private val runtimeSyncService: RuntimeSyncService,
    runtimeProvider: RuntimeProvider
) : CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {

    init {
        runCatching {
            ChainsStateTracker.updateState(chainId) { it.copy(runtimeVersion = ChainState.Status.Started) }
            launch {
                // await connection
                connection.state.first { it is SocketStateMachine.State.Connected }
                connection.socketService.subscriptionFlow(SubscribeRuntimeVersionRequest)
                    .map { it.runtimeVersionChange().specVersion }
                    .catch {
                        emitAll(
                            connection.socketService.subscriptionFlow(SubscribeStateRuntimeVersionRequest)
                                .map { it.runtimeVersionChange().specVersion }
                                .catch {
                                    val runtime = runtimeProvider.getOrNullWithTimeout()

                                    val version = runtime?.getVersionConstant()
                                        ?: connection.getVersionChainRpc()
                                        ?: connection.getVersionStateRpc()
                                        ?: error("Runtime version not obtained")

                                    emit(version)
                                }
                        )
                    }

                    .onEach { runtimeVersionResult ->
                        chainDao.updateRemoteRuntimeVersion(
                            chainId,
                            runtimeVersionResult
                        )

                        runtimeSyncService.applyRuntimeVersion(chainId)
                        ChainsStateTracker.updateState(chainId) { it.copy(runtimeVersion = ChainState.Status.Completed) }
                    }
                    .catch { error ->
                        ChainsStateTracker.updateState(chainId) {
                            it.copy(
                                runtimeVersion = ChainState.Status.Failed(
                                    error
                                )
                            )
                        }
                        Log.e(
                            "RuntimeVersionSubscription",
                            "Failed to subscribe runtime version for chain: $chainId. Error: $error"
                        )
                        error.printStackTrace()
                    }
                    .launchIn(this)
            }
        }
    }

    private fun RuntimeSnapshot.getVersionConstant(): Int? = runCatching {
        val versionConstant = metadata.system().constant("Version")
        val decodedVersion = versionConstant.type?.fromByteArrayOrNull(this, versionConstant.value)
        requireType<Struct.Instance>(decodedVersion)
        bindNumber(decodedVersion["specVersion"]).toInt()
    }.getOrNull()

    private suspend fun ChainConnection.getVersionChainRpc(): Int? = runCatching {
        socketService.executeAsync(
            request = RuntimeVersionRequest(),
            mapper = pojo<RuntimeVersion>().nonNull()
        ).specVersion
    }.getOrNull()

    private suspend fun ChainConnection.getVersionStateRpc(): Int? = runCatching {
        socketService.executeAsync(
            request = StateRuntimeVersionRequest(),
            mapper = pojo<RuntimeVersion>().nonNull()
        ).specVersion
    }.getOrNull()
}