package com.jsaiborne.vocalpitchdetector
import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class ConsentManager(private val activity: Activity) {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(activity)

    // Check if the "Privacy Settings" button should be visible in your UI
    fun isPrivacyOptionsRequired(): Boolean {
        return consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    fun gatherConsent(
        onConsentFinished: (error: String?) -> Unit
    ) {
        // For testing, uncomment the following block to force the GDPR form

//         In ConsentManager.kt or wherever you gather consent
//        val debugSettings = ConsentDebugSettings.Builder(activity)
//            .addTestDeviceHashedId("96ED80C1FB84FC5068E3B64760D45864") // Use your ID from Logcat
//            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
//            .build()
//
//        val params = ConsentRequestParameters.Builder()
//            .setConsentDebugSettings(debugSettings)
//            .build()

        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Consent info updated, now load/show form if required
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    onConsentFinished(formError?.message)
                }
            },
            { requestError ->
                onConsentFinished(requestError.message)
            }
        )
    }

    // Called when the user clicks your "Privacy Settings" button
    fun showPrivacyOptionsForm(onDismiss: (error: String?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            onDismiss(formError?.message)
        }
    }

    fun canRequestAds(): Boolean = consentInformation.canRequestAds()
}
