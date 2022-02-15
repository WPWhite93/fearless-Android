package jp.co.soramitsu.feature_wallet_impl.presentation.receive.di

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import jp.co.soramitsu.common.address.AddressIconGenerator
import jp.co.soramitsu.common.di.viewmodel.ViewModelKey
import jp.co.soramitsu.common.di.viewmodel.ViewModelModule
import jp.co.soramitsu.common.resources.ResourceManager
import jp.co.soramitsu.common.utils.QrCodeGenerator
import jp.co.soramitsu.feature_account_api.presentation.actions.ExternalAccountActions
import jp.co.soramitsu.feature_wallet_api.domain.CurrentAccountAddressUseCase
import jp.co.soramitsu.feature_wallet_api.domain.interfaces.WalletInteractor
import jp.co.soramitsu.feature_wallet_impl.presentation.AssetPayload
import jp.co.soramitsu.feature_wallet_impl.presentation.WalletRouter
import jp.co.soramitsu.feature_wallet_impl.presentation.receive.ReceiveViewModel
import jp.co.soramitsu.runtime.multiNetwork.ChainRegistry

@Module(includes = [ViewModelModule::class])
class ReceiveModule {

    @Provides
    @IntoMap
    @ViewModelKey(ReceiveViewModel::class)
    fun provideViewModel(
        interactor: WalletInteractor,
        qrCodeGenerator: QrCodeGenerator,
        addressIconGenerator: AddressIconGenerator,
        resourceManager: ResourceManager,
        externalAccountActions: ExternalAccountActions.Presentation,
        assetPayload: AssetPayload,
        router: WalletRouter,
        chainRegistry: ChainRegistry,
        currentAccountAddressUseCase: CurrentAccountAddressUseCase
    ): ViewModel {
        return ReceiveViewModel(
            interactor,
            qrCodeGenerator,
            addressIconGenerator,
            resourceManager,
            externalAccountActions,
            assetPayload,
            router,
            chainRegistry,
            currentAccountAddressUseCase
        )
    }

    @Provides
    fun provideViewModelCreator(
        fragment: Fragment,
        viewModelFactory: ViewModelProvider.Factory
    ): ReceiveViewModel {
        return ViewModelProvider(fragment, viewModelFactory).get(ReceiveViewModel::class.java)
    }
}
