package jp.co.soramitsu.common.data.network

import jp.co.soramitsu.core.model.Node
import jp.co.soramitsu.fearless_utils.extensions.requirePrefix

class AppLinksProvider(
    val termsUrl: String,
    val privacyUrl: String,

    private val externalAnalyzerTemplates: Map<ExternalAnalyzer, ExternalAnalyzerLinks>,

    val payoutsLearnMore: String,
    val twitterAccountTemplate: String,
    val setControllerLearnMore: String
) {

    fun getExternalEventUrl(
        analyzer: ExternalAnalyzer,
        eventId: String,
        networkType: Node.NetworkType
    ) = getExternalUrl(analyzer, eventId, networkType, ExternalAnalyzerLinks::event)

    fun getExternalTransactionUrl(
        analyzer: ExternalAnalyzer,
        hash: String,
        networkType: Node.NetworkType
    ) = getExternalUrl(analyzer, hashWithPrefix(hash), networkType, ExternalAnalyzerLinks::transaction)!!

    fun getExternalAddressUrl(
        analyzer: ExternalAnalyzer,
        address: String,
        networkType: Node.NetworkType
    ) = getExternalUrl(analyzer, address, networkType, ExternalAnalyzerLinks::account)!!

    private fun getExternalUrl(
        analyzer: ExternalAnalyzer,
        value: String,
        networkType: Node.NetworkType,
        extractor: (ExternalAnalyzerLinks) -> String?
    ): String? {
        val template = externalAnalyzerTemplates[analyzer] ?: error("No template for $analyzer")

        return extractor(template)?.format(networkPathSegment(networkType), value)
    }

    fun getTwitterAccountUrl(
        accountName: String
    ): String = twitterAccountTemplate.format(accountName)
}

class ExternalAnalyzerLinks(val transaction: String, val account: String, val event: String?)

enum class ExternalAnalyzer(val supportedNetworks: List<Node.NetworkType>) {
    SUBSCAN(
        supportedNetworks = listOf(Node.NetworkType.KUSAMA, Node.NetworkType.WESTEND, Node.NetworkType.POLKADOT, Node.NetworkType.ROCOCO)
    ),

    POLKASCAN(
        supportedNetworks = listOf(Node.NetworkType.KUSAMA, Node.NetworkType.POLKADOT)
    );

    fun isNetworkSupported(networkType: Node.NetworkType?) = networkType in supportedNetworks
}

private fun networkPathSegment(networkType: Node.NetworkType) = networkType.readableName.toLowerCase()

private fun hashWithPrefix(hash: String) = hash.requirePrefix("0x")
