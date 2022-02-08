package jp.co.soramitsu.feature_account_impl.presentation.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.zxing.integration.android.IntentIntegrator
import jp.co.soramitsu.common.base.BaseFragment
import jp.co.soramitsu.common.di.FeatureUtils
import jp.co.soramitsu.common.mixin.impl.observeBrowserEvents
import jp.co.soramitsu.common.qrScanner.QrScannerActivity
import jp.co.soramitsu.feature_account_api.di.AccountFeatureApi
import jp.co.soramitsu.feature_account_api.presentation.actions.ExternalAccountActions
import jp.co.soramitsu.feature_account_api.presentation.actions.copyAddressClicked
import jp.co.soramitsu.feature_account_impl.R
import jp.co.soramitsu.feature_account_impl.di.AccountFeatureComponent
import kotlinx.android.synthetic.main.fragment_profile.aboutTv
import kotlinx.android.synthetic.main.fragment_profile.accountView
import kotlinx.android.synthetic.main.fragment_profile.changePinCodeTv
import kotlinx.android.synthetic.main.fragment_profile.languageWrapper
import kotlinx.android.synthetic.main.fragment_profile.profileWallets
import kotlinx.android.synthetic.main.fragment_profile.profileBeacon
import kotlinx.android.synthetic.main.fragment_profile.selectedLanguageTv

class ProfileFragment : BaseFragment<ProfileViewModel>() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun initViews() {
        accountView.setWholeClickListener { viewModel.accountActionsClicked() }

        aboutTv.setOnClickListener { viewModel.aboutClicked() }

        profileWallets.setOnClickListener { viewModel.walletsClicked() }
        languageWrapper.setOnClickListener { viewModel.languagesClicked() }
        changePinCodeTv.setOnClickListener { viewModel.changePinCodeClicked() }
        profileBeacon.setOnClickListener { viewModel.beaconClicked() }
    }

    override fun inject() {
        FeatureUtils.getFeature<AccountFeatureComponent>(
            requireContext(),
            AccountFeatureApi::class.java
        )
            .profileComponentFactory()
            .create(this)
            .inject(this)
    }

    override fun subscribe(viewModel: ProfileViewModel) {
        observeBrowserEvents(viewModel)

        viewModel.selectedAccountLiveData.observe { account ->
            account.name.let(accountView::setTitle)
        }

        viewModel.accountIconLiveData.observe {
            accountView.setAccountIcon(it.image)
        }

        viewModel.selectedLanguageLiveData.observe {
            selectedLanguageTv.text = it.displayName
        }

        viewModel.showExternalActionsEvent.observeEvent(::showAccountActions)

        viewModel.totalBalanceLiveData.observe {
            accountView.setText(it)
        }

        viewModel.scanBeaconQrEvent.observeEvent {
            val integrator = IntentIntegrator.forSupportFragment(this).apply {
                setDesiredBarcodeFormats(IntentIntegrator.QR_CODE_TYPES)
                setPrompt("")
                setBeepEnabled(false)
                captureActivity = QrScannerActivity::class.java
            }

            integrator.initiateScan()
        }
    }

    private fun showAccountActions(payload: ExternalAccountActions.Payload) {
        ProfileActionsSheet(
            requireContext(),
            payload,
            viewModel::copyAddressClicked,
            viewModel::viewExternalClicked,
            viewModel::walletsClicked
        ).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        result?.contents?.let {
            viewModel.beaconQrScanned(it)
        }
    }
}
