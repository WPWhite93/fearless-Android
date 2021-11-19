package jp.co.soramitsu.feature_staking_impl.data.network.subquery

import jp.co.soramitsu.common.data.network.subquery.EraValidatorInfoQueryResponse
import jp.co.soramitsu.common.data.network.subquery.SubQueryResponse
import jp.co.soramitsu.common.data.network.subquery.TransactionHistoryRemote
import jp.co.soramitsu.feature_staking_impl.data.network.subquery.request.StakingEraValidatorInfosRequest
import jp.co.soramitsu.feature_staking_impl.data.network.subquery.request.StakingSumRewardRequest
import jp.co.soramitsu.runtime.BuildConfig
import jp.co.soramitsu.runtime.chain.remote.model.ChainRemote
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

interface StakingApi {

    @POST
    suspend fun getSumReward(
        @Url url: String,
        @Body body: StakingSumRewardRequest
    ): SubQueryResponse<TransactionHistoryRemote>

    @POST
    suspend fun getValidatorsInfo(
        @Url url: String,
        @Body body: StakingEraValidatorInfosRequest
    ): SubQueryResponse<EraValidatorInfoQueryResponse>

    @GET(BuildConfig.CHAINS_URL)
    suspend fun getChains(): List<ChainRemote>
}
