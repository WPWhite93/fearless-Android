package jp.co.soramitsu.app.di.app

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jp.co.soramitsu.app.root.navigation.Navigator
import jp.co.soramitsu.featureaccountimpl.presentation.AccountRouter
import jp.co.soramitsu.featurecrowdloanimpl.presentation.CrowdloanRouter
import jp.co.soramitsu.featureonboardingimpl.OnboardingRouter
import jp.co.soramitsu.featurestakingimpl.presentation.StakingRouter
import jp.co.soramitsu.featurewalletimpl.presentation.WalletRouter
import jp.co.soramitsu.splash.SplashRouter
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class NavigationModule {

    @Singleton
    @Provides
    fun provideNavigator(): Navigator = Navigator()

    @Singleton
    @Provides
    fun provideSplashRouter(navigator: Navigator): SplashRouter = navigator

    @Singleton
    @Provides
    fun provideOnboardingRouter(navigator: Navigator): OnboardingRouter = navigator

    @Singleton
    @Provides
    fun provideAccountRouter(navigator: Navigator): AccountRouter = navigator

    @Singleton
    @Provides
    fun provideWalletRouter(navigator: Navigator): WalletRouter = navigator

    @Singleton
    @Provides
    fun provideStakingRouter(navigator: Navigator): StakingRouter = navigator

    @Singleton
    @Provides
    fun provideCrowdloanRouter(navigator: Navigator): CrowdloanRouter = navigator
}
