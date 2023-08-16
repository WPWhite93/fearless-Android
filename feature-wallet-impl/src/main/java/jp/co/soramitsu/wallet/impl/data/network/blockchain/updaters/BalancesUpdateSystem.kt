package jp.co.soramitsu.wallet.impl.data.network.blockchain.updaters

import android.util.Log
import jp.co.soramitsu.account.api.domain.interfaces.AccountRepository
import jp.co.soramitsu.account.api.domain.model.MetaAccount
import jp.co.soramitsu.account.api.domain.model.accountId
import jp.co.soramitsu.common.data.network.rpc.BulkRetriever
import jp.co.soramitsu.common.data.network.runtime.binding.ExtrinsicStatusEvent
import jp.co.soramitsu.common.mixin.api.NetworkStateMixin
import jp.co.soramitsu.common.utils.requireException
import jp.co.soramitsu.common.utils.requireValue
import jp.co.soramitsu.core.models.Asset
import jp.co.soramitsu.core.updater.UpdateSystem
import jp.co.soramitsu.core.updater.Updater
import jp.co.soramitsu.core.utils.utilityAsset
import jp.co.soramitsu.coredb.dao.OperationDao
import jp.co.soramitsu.coredb.model.OperationLocal
import jp.co.soramitsu.runtime.ext.addressOf
import jp.co.soramitsu.runtime.multiNetwork.ChainRegistry
import jp.co.soramitsu.runtime.multiNetwork.chain.model.Chain
import jp.co.soramitsu.runtime.multiNetwork.getRuntimeOrNull
import jp.co.soramitsu.runtime.multiNetwork.getSocket
import jp.co.soramitsu.runtime.multiNetwork.getSocketOrNull
import jp.co.soramitsu.runtime.multiNetwork.toSyncIssue
import jp.co.soramitsu.runtime.network.subscriptionFlowCatching
import jp.co.soramitsu.shared_utils.runtime.AccountId
import jp.co.soramitsu.shared_utils.runtime.RuntimeSnapshot
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.storage.SubscribeStorageRequest
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.storage.storageChange
import jp.co.soramitsu.wallet.api.data.cache.AssetCache
import jp.co.soramitsu.wallet.api.data.cache.updateAsset
import jp.co.soramitsu.wallet.impl.data.mappers.mapOperationStatusToOperationLocalStatus
import jp.co.soramitsu.wallet.impl.data.network.blockchain.SubstrateRemoteSource
import jp.co.soramitsu.wallet.impl.data.network.blockchain.bindings.TransferExtrinsic
import jp.co.soramitsu.wallet.impl.data.network.model.constructBalanceKey
import jp.co.soramitsu.wallet.impl.data.network.model.handleBalanceResponse
import jp.co.soramitsu.wallet.impl.domain.model.Operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

private const val RUNTIME_AWAITING_TIMEOUT = 10_000L

class BalancesUpdateSystem(
    private val chainRegistry: ChainRegistry,
    private val accountRepository: AccountRepository,
    private val bulkRetriever: BulkRetriever,
    private val assetCache: AssetCache,
    private val substrateSource: SubstrateRemoteSource,
    private val operationDao: OperationDao,
    private val networkStateMixin: NetworkStateMixin,
) : UpdateSystem {

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun subscribeFlow(): Flow<Updater.SideEffect> {
        return combine(
            chainRegistry.syncedChains,
            accountRepository.selectedMetaAccountFlow()
        ) { chains, metaAccount ->
            chains to metaAccount
        }.flatMapLatest { (chains, metaAccount) ->
            val chainsFLow = chains.mapNotNull { chain ->
                subscribeChainBalances(chain, metaAccount)
                    .onFailure {
                        logError(chain, it)
                    }
                    .onSuccess {
                    }
                    .getOrNull()
            }

            combine(chainsFLow) { }.transform { }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun subscribeChainBalances(
        chain: Chain,
        metaAccount: MetaAccount
    ): Result<Flow<Any>> {
        val chainUpdateFlow =
            chainRegistry.getRuntimeProvider(chain.id).observeWithTimeout(RUNTIME_AWAITING_TIMEOUT)
                .flatMapMerge { runtimeResult ->
                    if (runtimeResult.isFailure) {
                        networkStateMixin.notifyChainSyncProblem(chain.toSyncIssue())
                        return@flatMapMerge flowOf(runtimeResult.requireException())
                    }
                    val runtime = runtimeResult.requireValue()

                    networkStateMixin.notifyChainSyncSuccess(chain.id)
                    val storageKeyToMapId =
                        buildStorageKeys(
                            chain,
                            metaAccount,
                            runtime
                        ).onFailure { return@flatMapMerge flowOf(Result.failure<Any>(it)) }
                            .getOrNull()
                            ?: return@flatMapMerge flowOf(Result.failure<Any>(RuntimeException("Can't get account id for meta account ${metaAccount.name}, chain: ${chain.name}")))

                    val socketService = runCatching { chainRegistry.getSocketOrNull(chain.id) }
                        .onFailure { return@flatMapMerge flowOf(Result.failure<Any>(it)) }
                        .getOrNull()
                        ?: return@flatMapMerge flowOf(Result.failure<Any>(RuntimeException("Error getting socket for chain ${chain.name}")))

                    val request = SubscribeStorageRequest(storageKeyToMapId.keys.toList())
                    combine(socketService.subscriptionFlowCatching(request)) { subscriptionsChangeResults ->
                        subscriptionsChangeResults.forEach { subscriptionChangeResult ->
                            val subscriptionChange =
                                subscriptionChangeResult.getOrNull() ?: return@combine
                            val storageChange = subscriptionChange.storageChange()

                            storageChange.changes.associate { it[0]!! to it[1] }
                                .mapKeys { (fullKey, _) -> storageKeyToMapId[fullKey]!! }
                                .mapValues { (metadata, hexRaw) ->

                                    val runtimeVersion =
                                        kotlin.runCatching {
                                            chainRegistry.getRemoteRuntimeVersion(
                                                chain.id
                                            )
                                        }
                                            .getOrNull() ?: 0

                                    handleBalanceResponse(
                                        runtime,
                                        metadata.asset.typeExtra,
                                        hexRaw,
                                        runtimeVersion
                                    )
                                }.toList()
                                .forEach {
                                    val (asset, metaId, accountId) = it.first
                                    val balanceData = it.second
                                    assetCache.updateAsset(
                                        metaId,
                                        accountId,
                                        asset,
                                        balanceData.getOrNull()
                                    )

                                    fetchTransfers(storageChange.block, chain, accountId)
                                }
                        }
                    }

                }
        return Result.success(chainUpdateFlow)
    }

    private fun singleUpdateFlow(): Flow<Unit> {
        return combine(
            chainRegistry.syncedChains,
            accountRepository.allMetaAccountsFlow()
        ) { chains, accounts ->
            chains.forEach singleChainUpdate@{ chain ->
                runCatching {
                    val runtime =
                        runCatching { chainRegistry.getRuntimeOrNull(chain.id) }.getOrNull()
                            ?: return@singleChainUpdate

                    val runtimeVersion = chainRegistry.getRemoteRuntimeVersion(chain.id) ?: 0
                    val socketService =
                        runCatching { chainRegistry.getSocket(chain.id) }.getOrNull()
                            ?: return@singleChainUpdate


                    val storageKeyToMapId =
                        accounts.filter { it.isSelected.not() }.mapNotNull { metaAccount ->
                            buildStorageKeys(chain, metaAccount, runtime)
                                .getOrNull()?.toList()
                        }.flatten().toMap()

                    val queryResults = withContext(Dispatchers.IO) {
                        bulkRetriever.queryKeys(
                            socketService,
                            storageKeyToMapId.keys.toList()
                        )
                    }
                    queryResults.mapKeys { (fullKey, _) -> storageKeyToMapId[fullKey]!! }
                        .mapValues { (metadata, hexRaw) ->
                            handleBalanceResponse(
                                runtime,
                                metadata.asset.typeExtra,
                                hexRaw,
                                runtimeVersion
                            )
                        }
                        .toList()
                        .forEach {
                            val (asset, metaId, accountId) = it.first
                            val balanceData = it.second
                            assetCache.updateAsset(
                                metaId,
                                accountId,
                                asset,
                                balanceData.getOrNull()
                            )
                        }
                }
                    .onFailure {
                        logError(chain, it)
                        return@singleChainUpdate
                    }
            }
        }.onStart { emit(Unit) }.flowOn(Dispatchers.Default)
    }

    private fun buildStorageKeys(
        chain: Chain,
        metaAccount: MetaAccount,
        runtime: RuntimeSnapshot
    ): Result<Map<String, StorageKeysMetadata>> {
        val accountId = metaAccount.accountId(chain)
            ?: return Result.failure(RuntimeException("Can't get account id for meta account ${metaAccount.name}, chain: ${chain.name}"))

        return Result.success(chain.assets.mapNotNull { asset ->
            constructBalanceKey(runtime, asset, accountId)?.let { key ->
                key to StorageKeysMetadata(
                    asset,
                    metaAccount.id,
                    accountId
                )
            }
        }.toMap())
    }

    data class StorageKeysMetadata(
        val asset: Asset,
        val metaAccountId: Long,
        val accountId: AccountId
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as StorageKeysMetadata

            if (asset != other.asset) return false
            if (metaAccountId != other.metaAccountId) return false
            if (!accountId.contentEquals(other.accountId)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = asset.hashCode()
            result = 31 * result + metaAccountId.hashCode()
            result = 31 * result + accountId.contentHashCode()
            return result
        }
    }

    override fun start(): Flow<Updater.SideEffect> {
        return combine(subscribeFlow(), singleUpdateFlow()) { sideEffect, _ -> sideEffect }
    }

    private fun logError(chain: Chain, error: Throwable) {
        Log.e(
            "BalancesUpdateSystem",
            "Failed to subscribe to balances in ${chain.name}: ${error.message}",
            error
        )
    }


    private suspend fun fetchTransfers(blockHash: String, chain: Chain, accountId: AccountId) {
        runCatching {
            val result =
                substrateSource.fetchAccountTransfersInBlock(chain.id, blockHash, accountId)

            val blockTransfers = result.getOrNull() ?: return

            val local = blockTransfers.map {
                val localStatus = when (it.statusEvent) {
                    ExtrinsicStatusEvent.SUCCESS -> Operation.Status.COMPLETED
                    ExtrinsicStatusEvent.FAILURE -> Operation.Status.FAILED
                    null -> Operation.Status.PENDING
                }

                createTransferOperationLocal(it.extrinsic, localStatus, accountId, chain)
            }

            withContext(Dispatchers.IO) { operationDao.insertAll(local) }
        }.onFailure {
            Log.d(
                "PaymentUpdater",
                "Failed to fetch transfers for chain ${chain.name} (${chain.id}) $it "
            )
        }
    }

    private suspend fun createTransferOperationLocal(
        extrinsic: TransferExtrinsic,
        status: Operation.Status,
        accountId: ByteArray,
        chain: Chain
    ): OperationLocal {
        val localCopy = operationDao.getOperation(extrinsic.hash)

        val fee = localCopy?.fee

        val senderAddress = chain.addressOf(extrinsic.senderId)
        val recipientAddress = chain.addressOf(extrinsic.recipientId)

        return OperationLocal.manualTransfer(
            hash = extrinsic.hash,
            chainId = chain.id,
            address = chain.addressOf(accountId),
            chainAssetId = chain.utilityAsset?.id.orEmpty(), // TODO do not hardcode chain asset id
            amount = extrinsic.amountInPlanks,
            senderAddress = senderAddress,
            receiverAddress = recipientAddress,
            fee = fee,
            status = mapOperationStatusToOperationLocalStatus(status),
            source = OperationLocal.Source.BLOCKCHAIN
        )
    }
}
